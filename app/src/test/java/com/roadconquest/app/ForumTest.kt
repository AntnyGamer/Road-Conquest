package com.roadconquest.app

import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.roadconquest.app.forum.ForumClient
import com.roadconquest.app.forum.ForumGifLoader
import com.roadconquest.app.util.Prefs
import com.roadconquest.app.util.UiTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class ForumTest {
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
            repeat(3) { activity.updateControls() }
            assertEquals(0,changes)
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

    @Test fun forumShowsHonestUnavailableSignInCooldownMuteAndBanStatesInBothThemes() {
        for (theme in listOf(UiTheme.LIGHT,UiTheme.DARK)) {
            Prefs.setUiTheme(RuntimeEnvironment.getApplication(),theme)
            val controller = Robolectric.buildActivity(ForumActivity::class.java).create()
            try {
                val activity=controller.get()
                val compose=activity.findViewById<Button>(R.id.forumCompose)
                val status=activity.findViewById<TextView>(R.id.forumStatus)
                activity.renderPage(page())
                assertEquals("Sign in to post",compose.text.toString()); assertTrue(compose.isEnabled)
                assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.forumEmpty).visibility)
                activity.renderPage(page(viewer(next=Instant.now().plusSeconds(540).toString())))
                assertFalse(compose.isEnabled); assertTrue(status.text.toString().contains("Next post"))
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
        } finally { controller.destroy() }
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
            "https://media.giphy.com.evil.test/a.gif","https://media.giphy.com:444/a.gif","https://media.giphy.com/page")) {
            assertFalse(url,ForumGifLoader.allowed(url))
        }
    }
}
