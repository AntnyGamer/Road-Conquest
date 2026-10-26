package com.roadconquest.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.roadconquest.app.forum.ForumClient
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountStoreDecryptionTest
import com.roadconquest.app.forum.ForumGifLoader
import com.roadconquest.app.util.Prefs
import com.roadconquest.app.util.UiTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.security.Security
import java.security.Provider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Instant
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ForumTest {
    private var installedFixture = false
    private var originalKeyStore: Provider? = null

    // UI tests exercise a genuine saved session, not an unsigned/mock forum user.
    // Production showComposer must keep its sign-in check.
    private fun signInForComposerTest() {
        val app = RuntimeEnvironment.getApplication()
        originalKeyStore = Security.getProvider("AndroidKeyStore")
        Security.removeProvider("AndroidKeyStore")
        Security.insertProviderAt(object : Provider("AndroidKeyStore", 1.0, "Forum test keystore") {
            init { put("KeyStore.AndroidKeyStore", AccountStoreDecryptionTest.FixtureKeyStore::class.java.name) }
        }, 1)
        installedFixture = true
        AccountStore.save(app, AccountStore.Session("Driver", "forum-test-session", false))
    }

    @After fun resetFixture() {
        if (!installedFixture) return
        RuntimeEnvironment.getApplication().getSharedPreferences("roadconquest_account", 0)
            .edit().clear().commit()
        Security.removeProvider("AndroidKeyStore")
        originalKeyStore?.let { Security.addProvider(it) }
        installedFixture = false
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    fun previousAccountActionCannotUnlockAnInFlightActionForTheCurrentAccount() {
        signInForComposerTest()
        val originalEndpoint = AccountClient.endpointOverrideForTests
        AccountClient.endpointOverrideForTests = ""
        val controller = Robolectric.buildActivity(ForumActivity::class.java).create()
        val firstStarted = CountDownLatch(1)
        val firstFinish = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondFinish = CountDownLatch(1)
        try {
            val activity = controller.get()
            val scope = ForumActivity::class.java.getDeclaredField("sessionState")
                .apply { isAccessible = true }.get(activity) as com.roadconquest.app.forum.ForumSessionState
            val writing = ForumActivity::class.java.getDeclaredField("writing").apply { isAccessible = true }
            val action = ForumActivity::class.java.getDeclaredMethod("action", Function1::class.java, String::class.java)
                .apply { isAccessible = true }
            scope.switchTo("forum-test-session")
            val first: (String) -> Unit = { firstStarted.countDown(); check(firstFinish.await(10, TimeUnit.SECONDS)) }
            action.invoke(activity, first, "First action complete")
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS))
            AccountStore.save(RuntimeEnvironment.getApplication(), AccountStore.Session("Other", "second-session", false))
            // Reproduce refresh's account transition without starting a feed request.
            scope.switchTo("second-session")
            writing.setBoolean(activity, false)
            val second: (String) -> Unit = { secondStarted.countDown(); check(secondFinish.await(10, TimeUnit.SECONDS)) }
            action.invoke(activity, second, "Second action complete")
            firstFinish.countDown()
            // The serial worker posts the first callback before starting the second action.
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("The old account's response must retain the current action's busy guard", writing.getBoolean(activity))
        } finally {
            firstFinish.countDown()
            secondFinish.countDown()
            controller.destroy()
            AccountClient.endpointOverrideForTests = originalEndpoint
        }
    }

    @Test fun clearingRecycledGifAlsoClearsItsPreviousAccessibilityLabel() {
        val view = ImageView(RuntimeEnvironment.getApplication())
        val loader = ForumGifLoader()
        try {
            view.contentDescription = "Shared GIF"
            loader.clear(view)
            assertNull(view.contentDescription)
            // A recycled row must also forget an old failed-load label.
            view.contentDescription = "GIF unavailable. Refresh to retry."
            loader.clear(view)
            assertNull(view.contentDescription)
        } finally {
            loader.close()
        }
    }

    @Test fun gifHashesKeepExactLowercaseSha256ValidationAndBinaryValues() {
        val digest = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        val hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertArrayEquals(digest,ForumGifLoader.parseHash(hex))
        val allBytes=ByteArray(32) { (it * 8).toByte() }
        val allHex=allBytes.joinToString("") { "%02x".format(it) }
        assertArrayEquals(allBytes,ForumGifLoader.parseHash(allHex))
        for(invalid in listOf("",hex.dropLast(1),hex+"0",hex.uppercase(),"g"+hex.drop(1))) {
            assertNull(invalid,ForumGifLoader.parseHash(invalid))
        }
    }

    @Test fun repeatedControlUpdatesDoNotReannounceUnchangedStatusOrDuplicateLoadedPosts() {
        val controller=Robolectric.buildActivity(ForumActivity::class.java).create()
        try {
            val activity=controller.get()
            val post=ForumClient.Post("id","Explorer","A discovery",null,null,Instant.now().toString(),false,false,emptyMap(),null)
            activity.renderPage(page(viewer()).copy(posts=listOf(post,post)))
            activity.renderPage(page(viewer()).copy(posts=listOf(post)),append=true)
            val list=activity.findViewById<ListView>(R.id.forumList)
            assertEquals(2,list.adapter.count) // One post plus the load-more footer.
            var changes=0
            activity.findViewById<TextView>(R.id.forumStatus).addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { changes++ }
                override fun afterTextChanged(s:android.text.Editable?) {}
            })
            val unchangedControls = listOf(
                activity.findViewById<TextView>(R.id.forumCompose),
                activity.findViewById<TextView>(R.id.forumPlus),
                activity.findViewById<TextView>(R.id.forumEmptyTitle),
                activity.findViewById<TextView>(R.id.forumEmptyBody)
            )
            val changesByControl = IntArray(unchangedControls.size)
            unchangedControls.forEachIndexed { index,control ->
                control.addTextChangedListener(object:android.text.TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { changesByControl[index]++ }
                    override fun afterTextChanged(s:android.text.Editable?) {}
                })
            }
            repeat(3) { activity.updateControls() }
            assertEquals(0,changes)
            assertTrue("Unchanged controls should not trigger repeated text layouts",
                changesByControl.all { it == 0 })
        } finally { controller.destroy() }
    }
    @Test fun aFailedRefreshStaysVisibleWhenTheLocalCooldownUpdatesAndClearsOnSuccess() {
        val controller=Robolectric.buildActivity(ForumActivity::class.java).create()
        try {
            val activity=controller.get()
            activity.renderPage(page(viewer(next=Instant.now().plusSeconds(300).toString())))
            activity.showLoadError("Connection lost. Tap Refresh to retry.")
            activity.updateControls()
            assertEquals("Connection lost. Tap Refresh to retry.",activity.findViewById<TextView>(R.id.forumStatus).text.toString())
            activity.renderPage(page(viewer()))
            assertFalse(activity.findViewById<TextView>(R.id.forumStatus).text.toString().contains("Connection lost"))
        } finally { controller.destroy() }
    }
    private fun viewer(banned: Boolean = false, until: String? = null, next: String? = null) =
        ForumClient.Viewer("Driver",0,until,banned,false,"bound",false,next)
    private fun page(viewer: ForumClient.Viewer? = null, posting: Boolean = true) =
        ForumClient.Page(true,posting,false,emptyList(),null,viewer)

    @Test fun plusChangesUpdateTheOpenComposerWithoutLosingItsDraftOrRulesAgreement() {
        signInForComposerTest()
        for (theme in listOf(UiTheme.LIGHT,UiTheme.DARK)) {
            Prefs.setUiTheme(RuntimeEnvironment.getApplication(),theme)
            val controller = Robolectric.buildActivity(ForumActivity::class.java).create()
            try {
                val activity = controller.get()
                activity.renderPage(page(viewer()))
                ForumActivity::class.java.getDeclaredMethod("showComposer").apply { isAccessible = true }.invoke(activity)
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                val text = dialog.findViewById<EditText>(R.id.forumDraftText)
                val gif = dialog.findViewById<EditText>(R.id.forumDraftGif)
                val rules = dialog.findViewById<CheckBox>(R.id.forumDraftRules)
                text.setText("Keep this discovery while restoring Plus")
                gif.setText("https://media.giphy.com/example.gif")
                rules.isChecked = true
                assertEquals(View.GONE,gif.visibility)
                assertEquals(View.VISIBLE,dialog.findViewById<View>(R.id.forumGifUpsell).visibility)

                activity.renderPage(page(viewer().copy(plus=true)))
                assertTrue(dialog.isShowing)
                assertEquals(View.VISIBLE,gif.visibility)
                assertEquals(View.VISIBLE,dialog.findViewById<View>(R.id.forumGifHelp).visibility)
                assertEquals(View.GONE,dialog.findViewById<View>(R.id.forumGifUpsell).visibility)
                activity.renderPage(page(viewer()))
                assertEquals(View.GONE,gif.visibility)
                assertEquals(View.GONE,dialog.findViewById<View>(R.id.forumGifHelp).visibility)
                assertEquals(View.VISIBLE,dialog.findViewById<View>(R.id.forumGifUpsell).visibility)
                assertEquals("Keep this discovery while restoring Plus",text.text.toString())
                assertEquals("https://media.giphy.com/example.gif",gif.text.toString())
                assertTrue(rules.isChecked)
            } finally { controller.destroy() }
        }
    }

    @Test fun forumShowsHonestUnavailableSignInCooldownMuteAndBanStatesInBothThemes() {
        for (theme in listOf(UiTheme.LIGHT,UiTheme.DARK)) {
            Prefs.setUiTheme(RuntimeEnvironment.getApplication(),theme)
            val controller = Robolectric.buildActivity(ForumActivity::class.java).create()
            try {
                val activity=controller.get()
                val compose=activity.findViewById<Button>(R.id.forumCompose)
                val status=activity.findViewById<TextView>(R.id.forumStatus)
                activity.renderPage(page().copy(available=false, posting=false))
                assertEquals("Posting unavailable",compose.text.toString())
                assertFalse(compose.isEnabled)
                assertEquals("Community unavailable",activity.findViewById<TextView>(R.id.forumEmptyTitle).text.toString())
                assertFalse(status.text.toString().contains("Sign in"))
                activity.renderPage(page())
                assertEquals("Sign in to post",compose.text.toString()); assertTrue(compose.isEnabled)
                assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.forumEmpty).visibility)
                activity.renderPage(page(viewer(next=Instant.now().plusSeconds(540).toString())))
                assertFalse(compose.isEnabled); assertEquals("You can post again in 9 minutes.", status.text.toString())
                activity.renderPage(page(viewer(next=Instant.now().plusSeconds(55).toString())))
                assertEquals("You can post again in 1 minute.", status.text.toString())
                activity.renderPage(page(viewer(until=Instant.now().plusSeconds(3600).toString())))
                assertFalse(compose.isEnabled); assertTrue(status.text.toString().contains("mute"))
                activity.renderPage(page(viewer(banned=true)))
                assertFalse(compose.isEnabled); assertTrue(status.text.toString().contains("banned"))
                assertFalse(activity.findViewById<Button>(R.id.forumPlus).isEnabled)
                activity.renderPage(page(viewer(),posting=false))
                assertFalse(compose.isEnabled); assertTrue(status.text.toString().contains("temporarily unavailable"))
                activity.renderPage(page(viewer(next=Instant.now().minusSeconds(1).toString())))
                assertTrue(compose.isEnabled)
            } finally { controller.destroy() }
        }
    }

    @Test fun plusPostsHaveSubtleHighlightAndAccessibleReactionsWithoutChangingPostText() {
        val controller=Robolectric.buildActivity(ForumActivity::class.java).create()
        try {
            val activity=controller.get()
            val post=ForumClient.Post("id","Explorer","Found a quiet scenic road.",null,null,Instant.now().toString(),true,false,
                mapOf("like" to 2,"love" to 1,"celebrate" to 0),"love")
            activity.renderPage(page(viewer()).copy(posts=listOf(post)))
            val list=activity.findViewById<ListView>(R.id.forumList)
            val row=list.adapter.getView(0,null,list)
            assertEquals(post.text,row.findViewById<TextView>(R.id.postBody).text.toString())
            assertTrue(row.findViewById<Button>(R.id.postLove).isSelected)
            assertNotNull(row.findViewById<Button>(R.id.postLike).contentDescription)
            assertEquals(View.GONE,row.findViewById<View>(R.id.postGif).visibility)
            // A legacy or malformed post can contain a URL without the
            // mandatory SHA-256 digest. Its recycled row must not show blank media.
            val partial = post.copy(gifUrl = "https://media.giphy.com/a.gif", gifHash = null)
            activity.renderPage(page(viewer()).copy(posts = listOf(partial)))
            val recycled = list.adapter.getView(0, row, list)
            assertEquals(View.GONE,recycled.findViewById<View>(R.id.postGif).visibility)
        } finally { controller.destroy() }
    }

    @Test fun postsAndComposerAreFullyOpaqueInLightDarkAndGold() {
        signInForComposerTest()
        for ((theme, gold) in listOf(UiTheme.LIGHT to false, UiTheme.DARK to false, UiTheme.DARK to true)) {
            val app = RuntimeEnvironment.getApplication()
            Prefs.setUiTheme(app, theme)
            Prefs.setGoldUiEnabled(app, gold)
            val controller = Robolectric.buildActivity(ForumActivity::class.java).create()
            try {
                val activity = controller.get()
                val post = ForumClient.Post("id", "Explorer", "An open road", null, null,
                    Instant.now().toString(), false, false, emptyMap(), null)
                activity.renderPage(page(viewer()).copy(posts = listOf(post)))
                val list = activity.findViewById<ListView>(R.id.forumList)
                val card = list.adapter.getView(0, null, list)
                assertEquals(255, Color.alpha((card.background as GradientDrawable).color!!.defaultColor))
                ForumActivity::class.java.getDeclaredMethod("showComposer").apply { isAccessible = true }.invoke(activity)
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                val panel = dialog.findViewById<EditText>(R.id.forumDraftText).parent as View
                assertEquals(255, Color.alpha((panel.background as GradientDrawable).color!!.defaultColor))
                dialog.dismiss()
            } finally {
                controller.destroy()
                Prefs.setGoldUiEnabled(app, false)
            }
        }
    }

    @Test fun serverPagesParseOptionalCooldownAndRejectOversizedPages() {
        val body=JSONObject().put("available",true).put("posts",JSONArray()).put("viewer",JSONObject()
            .put("username","Driver").put("billing_account_id","bound").put("next_post_at","2026-10-24T12:10:00Z"))
        assertEquals("2026-10-24T12:10:00Z",ForumClient.parsePage(body).viewer?.nextPostAt)
        assertFalse(ForumClient.parsePage(body).purchases)
        body.put("posts",JSONArray(List(21) { JSONObject() }))
        assertThrows(IllegalArgumentException::class.java) { ForumClient.parsePage(body) }
    }

    @Test fun gifMediaNeverAcceptsArbitraryHostsCredentialsOrNonHttpsUrls() {
        assertTrue(ForumGifLoader.allowed("https://media.giphy.com/media/example/giphy.gif"))
        assertTrue(ForumGifLoader.allowed("https://media.tenor.com/example.gif"))
        for(url in listOf("http://media.giphy.com/a.gif","https://user@media.giphy.com/a.gif",
            "https://media.giphy.com.evil.test/a.gif","https://media.giphy.com:444/a.gif","https://media.giphy.com/page",
            "https://media.giphy.com/a.gif#","https://media.giphy.com/a.gif#fragment")) {
            assertFalse(url,ForumGifLoader.allowed(url))
        }
    }
}
