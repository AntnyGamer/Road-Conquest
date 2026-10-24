package com.roadconquest.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.forum.ForumBilling
import com.roadconquest.app.forum.ForumClient
import com.roadconquest.app.forum.ForumGifLoader
import com.roadconquest.app.util.Appearance
import com.roadconquest.app.util.ForegroundSession
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

class ForumActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val gifs = ForumGifLoader()
    private val posts = mutableListOf<ForumClient.Post>()
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var compose: Button
    private lateinit var plus: Button
    private lateinit var morePosts: Button
    private val adapter = PostsAdapter()
    private var page: ForumClient.Page? = null
    private var loading = false
    private var writing = false
    private var billing: ForumBilling? = null
    private var purchaseDialog: Dialog? = null
    private var checkout: Button? = null
    private var purchaseMessage: TextView? = null
    private var ownershipRequested = false
    private var restoreRequested = false
    private var foreground = false
    private var composer: Dialog? = null
    private var draftText = ""
    private var draftGif = ""
    private var submissionId = UUID.randomUUID().toString()
    private var submittedContent = ""
    private var restoreComposer = false
    private val cooldownTick = object : Runnable {
        override fun run() {
            if (foreground && active()) {
                updateControls()
                val remaining = cooldownSeconds()
                status.postDelayed(this,if (remaining > 0) ((remaining - 1) % 60 + 1) * 1000 else 60_000)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Appearance.wrap(newBase)) }
    override fun onStart() { super.onStart(); ForegroundSession.app.onStart() }
    override fun onStop() { ForegroundSession.app.onStop(isChangingConfigurations); super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window,window.decorView).apply {
            isAppearanceLightStatusBars = !Appearance.isDark(this@ForumActivity)
            isAppearanceLightNavigationBars = !Appearance.isDark(this@ForumActivity)
        }
        setContentView(R.layout.activity_forum)
        val root = findViewById<View>(R.id.forumRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left,safe.top,safe.right,safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        draftText = savedInstanceState?.getString("draft_text") ?: ""
        draftGif = savedInstanceState?.getString("draft_gif") ?: ""
        submissionId = savedInstanceState?.getString("submission_id") ?: submissionId
        submittedContent = savedInstanceState?.getString("submitted_content") ?: ""
        restoreComposer = savedInstanceState?.getBoolean("composer_open") ?: false
        list = findViewById(R.id.forumList)
        status = findViewById(R.id.forumStatus)
        compose = findViewById(R.id.forumCompose)
        plus = findViewById(R.id.forumPlus)
        morePosts = Button(this,null,0,R.style.RoadConquest_FilterAction).apply {
            text = "Load more"
            setOnClickListener { refresh(true) }
        }
        list.addFooterView(morePosts,null,true)
        list.adapter = adapter
        findViewById<View>(R.id.forumBack).setOnClickListener { finish() }
        findViewById<View>(R.id.forumRefresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.forumMore).setOnClickListener { options(it) }
        compose.setOnClickListener { if (sessionToken() == null) signIn() else showComposer() }
        plus.setOnClickListener { showPlus() }
    }

    override fun onResume() { super.onResume(); foreground = true; gifs.setForeground(true); status.post(cooldownTick); refresh() }
    override fun onPause() { foreground = false; gifs.setForeground(false); status.removeCallbacks(cooldownTick); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) {
        saveDraft()
        out.putString("draft_text",draftText); out.putString("draft_gif",draftGif)
        out.putString("submission_id",submissionId); out.putString("submitted_content",submittedContent)
        out.putBoolean("composer_open",composer?.isShowing == true)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() {
        composer?.dismiss(); purchaseDialog?.dismiss(); billing?.close(); gifs.close()
        worker.shutdownNow()
        super.onDestroy()
    }
    private fun sessionToken() = AccountStore.load(this)?.token
    private fun signIn() { startActivity(Intent(this,AccountActivity::class.java)) }
    private fun message(text: String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun active() = !isDestroyed && !isFinishing

    private fun refresh(loadMore: Boolean = false) {
        if (loading || writing || !active()) return
        val next = if (loadMore) page?.next ?: return else null
        val token = sessionToken()
        loading = true
        updateControls()
        worker.execute {
            val result = runCatching { ForumClient.posts(token,next) }
            runOnUiThread {
                if (!active()) return@runOnUiThread
                loading = false
                if (sessionToken() != token) { refresh(); return@runOnUiThread }
                result.fold(onSuccess = { received ->
                    page = received
                    if (!loadMore) posts.clear()
                    val known = posts.mapTo(HashSet()) { it.id }
                    posts.addAll(received.posts.filter { known.add(it.id) })
                    adapter.notifyDataSetChanged()
                    updateControls()
                    if (!received.available) status.text = "Community is currently unavailable. Please try again later."
                    if (restoreComposer && received.viewer != null && received.posting && !restricted(received.viewer)) {
                        restoreComposer = false
                        showComposer()
                    }
                    if (!ownershipRequested && received.purchases && received.viewer?.plus == false) {
                        ownershipRequested = true
                        billing().restore(silent = true)
                    }
                }, onFailure = { error ->
                    if (error is AccountClient.ApiException && error.status == 401 && sessionToken() == token) {
                        AccountStore.clear(this)
                        ownershipRequested = false
                        refresh()
                    } else {
                        status.text = error.message ?: "Could not load the community. Tap Refresh to retry."
                        updateControls(keepStatus = true)
                    }
                })
            }
        }
    }

    internal fun renderForPreview(received: ForumClient.Page) {
        page = received; posts.clear(); posts.addAll(received.posts); adapter.notifyDataSetChanged(); updateControls()
    }
    private fun restricted(viewer: ForumClient.Viewer?): Boolean = viewer?.banned == true ||
        viewer?.mutedUntil?.let { runCatching { Instant.parse(it).isAfter(Instant.now()) }.getOrDefault(false) } == true
    private fun cooldownSeconds(): Long = page?.viewer?.nextPostAt?.let {
        runCatching { ((Instant.parse(it).toEpochMilli() - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0) }.getOrDefault(0)
    } ?: 0

    private fun updateControls(keepStatus: Boolean = false) {
        val current = page
        val viewer = current?.viewer
        val busy = loading || writing
        val wait = cooldownSeconds()
        compose.isEnabled = !busy && current?.available == true && current.posting && !restricted(viewer) && wait == 0L
        compose.text = if (viewer == null) "Sign in to post" else getString(R.string.forum_new_post)
        plus.isEnabled = !busy && current?.available == true
        plus.text = if (viewer?.plus == true) "Plus active" else getString(R.string.forum_plus)
        morePosts.visibility = if (current?.next != null) View.VISIBLE else View.GONE
        morePosts.isEnabled = !busy
        if (!keepStatus) status.text = when {
            writing -> "Sending…"
            loading -> getString(R.string.forum_loading)
            current == null || !current.available -> "Community is currently unavailable. Please try again later."
            viewer?.banned == true -> "Your account is banned from forum participation. Your driving progress is unaffected."
            restricted(viewer) -> "Forum mute ends " + date(viewer!!.mutedUntil!!) + ". You can still read posts."
            !current.posting -> "Posting is temporarily unavailable. You can still read the community."
            viewer == null -> "Explore together. Sign in when you want to join in."
            wait > 0 -> "Next post in ${(wait + 59) / 60} min · One post every 10 minutes."
            viewer.strikes > 0 -> "Signed in as ${viewer.username} · ${viewer.strikes} moderation violation(s)."
            else -> "Share discoveries. Find inspiration. Be kind."
        }
        findViewById<TextView>(R.id.forumEmptyTitle).text = if (loading) "One moment…" else getString(R.string.forum_empty_title)
        list.visibility = if (posts.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.forumEmpty).visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
    }
    private fun date(value: String) = runCatching {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date.from(Instant.parse(value)))
    }.getOrDefault(value)

    private fun action(work: (String) -> Unit, success: String) {
        val token = sessionToken() ?: run { signIn(); return }
        if (writing || loading) return
        writing = true; updateControls()
        worker.execute {
            val result = runCatching { work(token) }
            runOnUiThread {
                if (!active()) return@runOnUiThread
                writing = false
                if (sessionToken() != token) { refresh(); return@runOnUiThread }
                result.fold(onSuccess = { message(success); refresh() },onFailure = {
                    message(it.message ?: "The action could not be completed. Please try again.")
                    updateControls()
                })
            }
        }
    }

    private fun options(anchor: View) {
        PopupMenu(this,anchor).apply {
            menu.add("Community rules").setOnMenuItemClickListener { rules(); true }
            menu.add("Help or appeal").setOnMenuItemClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/AntnyGamer/RoadConquest-Beta/issues"))) }
                    .onFailure { message("Open the Road Conquest GitHub Issues page for help.") }; true
            }
            if (sessionToken() != null) {
                menu.add("Blocked accounts").setOnMenuItemClickListener { blockedAccounts(); true }
                menu.add("Restore Forum Plus").setOnMenuItemClickListener { restoreRequested = true; billing().restore(); true }
            }
            if (page?.viewer?.moderator == true) {
                menu.add("Review reports").setOnMenuItemClickListener { reports(); true }
                menu.add("Review an appeal").setOnMenuItemClickListener { appeal(); true }
            }
            show()
        }
    }
    private fun rules() {
        AlertDialog.Builder(this).setTitle("Community rules").setMessage(R.string.forum_rules).setPositiveButton("Got it",null).show()
    }
    private fun saveDraft() {
        composer?.findViewById<EditText>(R.id.forumDraftText)?.let { draftText = it.text.toString() }
        composer?.findViewById<EditText>(R.id.forumDraftGif)?.let { draftGif = it.text.toString() }
    }
    private fun panel(title: String): Pair<Dialog,LinearLayout> {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22),dp(22),dp(22),dp(22))
            setBackgroundResource(R.drawable.tour_card_background)
        }
        dialog.setContentView(android.widget.ScrollView(this).apply { addView(content) })
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        content.addView(TextView(this).apply {
            text = title; textSize = 25f; setTypeface(typeface,Typeface.BOLD)
            setTextColor(Appearance.color(this@ForumActivity,R.attr.rcTextPrimary)); isAccessibilityHeading = true
        })
        dialog.setTitle(title)
        return dialog to content
    }
    private fun showPanel(dialog: Dialog) {
        dialog.show()
        dialog.window?.setLayout(minOf(dp(480),resources.displayMetrics.widthPixels - dp(32)),ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setPadding(0,dp(12),0,dp(12))
        setTextColor(Appearance.color(this@ForumActivity,R.attr.rcTextSecondary))
    }
    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; minHeight = dp(48); setOnClickListener { onClick() }
    }

    private fun showComposer() {
        if (composer?.isShowing == true || restricted(page?.viewer) || cooldownSeconds() > 0) return
        val (dialog,content) = panel("Share a discovery")
        composer = dialog
        val text = EditText(this).apply {
            id = R.id.forumDraftText; hint = "A favorite road, a new place, or a useful tip…"; minLines = 3; maxLines = 8
            filters = arrayOf(InputFilter.LengthFilter(1200)); setText(draftText)
            setTextColor(Appearance.color(this@ForumActivity,R.attr.rcTextPrimary))
        }
        content.addView(text,LinearLayout.LayoutParams(-1,-2))
        val gif = EditText(this).apply {
            id = R.id.forumDraftGif; hint = "Direct Giphy or Tenor .gif link"; maxLines = 2
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            filters = arrayOf(InputFilter.LengthFilter(1000)); setText(draftGif)
            visibility = if (page?.viewer?.plus == true) View.VISIBLE else View.GONE
            setTextColor(Appearance.color(this@ForumActivity,R.attr.rcTextPrimary))
        }
        content.addView(gif)
        if (page?.viewer?.plus == true) content.addView(label("GIFs: up to 2 MB, 512 × 512 pixels and 60 frames."))
        else content.addView(Button(this,null,0,R.style.RoadConquest_TourQuietAction).apply {
            this.text = "GIFs and highlights · Forum Plus"; setOnClickListener { showPlus() }
        })
        content.addView(Button(this,null,0,R.style.RoadConquest_TourQuietAction).apply { this.text = "Read community rules"; setOnClickListener { rules() } })
        val agreed = CheckBox(this).apply {
            this.text = "I agree to the community rules. My post will be public."; minHeight = dp(48)
            setTextColor(Appearance.color(this@ForumActivity,R.attr.rcTextSecondary))
        }
        content.addView(agreed)
        content.addView(label("One post every 10 minutes. Forum Plus follows the same cooldown."))
        lateinit var send: Button
        send = button("Post") {
            val token = sessionToken() ?: return@button
            if (writing || loading) return@button
            if (cooldownSeconds() > 0) { message("You can post once every 10 minutes."); return@button }
            if (!agreed.isChecked) { message("Read and agree to the community rules first."); return@button }
            saveDraft()
            val gifUrl = draftGif.trim().takeIf { it.isNotBlank() && page?.viewer?.plus == true }
            if (draftText.isBlank() && gifUrl == null) { message("Write a post or attach a GIF."); return@button }
            if (gifUrl != null && !ForumGifLoader.allowed(gifUrl)) { message("Use a direct HTTPS .gif link from Giphy or Tenor."); return@button }
            val signature = draftText + "\u0000" + gifUrl.orEmpty()
            if (signature != submittedContent) { submissionId = UUID.randomUUID().toString(); submittedContent = signature }
            val submittedText = draftText
            val requestId = submissionId
            writing = true; updateControls()
            text.isEnabled = false; gif.isEnabled = false
            send.isEnabled = false
            worker.execute {
                val result = runCatching { ForumClient.post(token,submittedText,gifUrl,requestId) }
                runOnUiThread {
                    if (!active()) return@runOnUiThread
                    writing = false; updateControls()
                    text.isEnabled = true; gif.isEnabled = true
                    send.isEnabled = true
                    if (sessionToken() != token) { dialog.dismiss(); refresh(); return@runOnUiThread }
                    result.fold(onSuccess = { response ->
                        dialog.dismiss(); composer = null; draftText = ""; draftGif = ""; submittedContent = ""
                        if (response.optBoolean("removed")) {
                            val penalty = when {
                                response.optBoolean("banned") -> "This account is now banned from forum participation."
                                !response.isNull("muted_until") -> "Forum participation is muted until " + date(response.getString("muted_until")) + "."
                                else -> "A second violation results in a 7-day forum mute."
                            }
                            AlertDialog.Builder(this).setTitle("Post removed")
                                .setMessage("The post violated the community rules. $penalty\n\nPost ID: ${response.optString("id")}")
                                .setPositiveButton("OK",null).show()
                        } else message("Posted to the community.")
                        refresh()
                    },onFailure = {
                        message(it.message ?: "Could not submit. Try again; retries will not add another strike.")
                        if (it is AccountClient.ApiException && it.status in listOf(403,429)) refresh()
                    })
                }
            }
        }
        content.addView(send)
        dialog.setOnDismissListener { saveDraft() }
        showPanel(dialog)
    }

    private fun billing(): ForumBilling = billing ?: ForumBilling(this,{ price,feedback ->
        checkout?.apply { isEnabled = price != null && page?.purchases == true; text = if (price == null) "Purchase unavailable" else "Continue · $price" }
        if (feedback != null) {
            purchaseMessage?.text = feedback
            if (restoreRequested) { message(feedback); restoreRequested = false }
        }
    },{ token ->
        val session = sessionToken()
        if (session != null) worker.execute {
            val result = runCatching { ForumClient.purchase(session,token) }
            runOnUiThread {
                if (!active() || sessionToken() != session) return@runOnUiThread
                result.fold(onSuccess = {
                    if (it.optBoolean("plus")) { purchaseDialog?.dismiss(); message("Forum Plus is active."); refresh() }
                    else message("Google Play has not confirmed an active purchase.")
                },onFailure = { message(it.message ?: "Purchase verification failed. Use Restore Forum Plus to retry.") })
            }
        }
    }).also { billing = it }

    private fun showPlus() {
        if (sessionToken() == null) { signIn(); return }
        if (page?.viewer?.plus == true) { message("Forum Plus is already active for this account."); return }
        val (dialog,content) = panel("A little extra personality.")
        purchaseDialog = dialog
        content.addView(label(getString(R.string.forum_plus_description)))
        purchaseMessage = label(if (page?.purchases == true) "Checking Google Play price…" else "Forum Plus is currently unavailable.")
        content.addView(purchaseMessage)
        checkout = button("Checking price…") {
            page?.viewer?.let { billing().buy(this,it.billingAccountId) }
        }.apply { isEnabled = false }
        content.addView(checkout)
        content.addView(Button(this,null,0,R.style.RoadConquest_TourQuietAction).apply {
            text = "Restore purchase"; setOnClickListener { restoreRequested = true; billing().restore() }
        })
        dialog.setOnDismissListener { checkout = null; purchaseMessage = null }
        showPanel(dialog)
        if (page?.purchases == true) billing().price()
    }

    private fun blockedAccounts() {
        val token = sessionToken() ?: return
        worker.execute {
            val result = runCatching { ForumClient.blocks(token).getJSONArray("accounts") }
            runOnUiThread {
                if (!active() || sessionToken() != token) return@runOnUiThread
                result.fold(onSuccess = { array ->
                    val accounts = (0 until array.length()).map { array.getString(it) }
                    if (accounts.isEmpty()) message("You have no blocked accounts.")
                    else AlertDialog.Builder(this).setTitle("Unblock an account")
                        .setItems(accounts.toTypedArray()) { _,index -> action({ ForumClient.block(it,accounts[index],false) },"Account unblocked.") }
                        .setNegativeButton("Cancel",null).show()
                },onFailure = { message("Could not load blocked accounts.") })
            }
        }
    }
    private fun reports() {
        val token = sessionToken() ?: return
        worker.execute {
            val result = runCatching { ForumClient.reports(token).getJSONArray("posts") }
            runOnUiThread {
                if (!active() || sessionToken() != token) return@runOnUiThread
                result.fold(onSuccess = { array ->
                    if (array.length() == 0) message("No reports are waiting for review.")
                    else AlertDialog.Builder(this).setTitle("Reported posts")
                        .setItems(Array(array.length()) { i -> array.getJSONObject(i).let { "${it.getString("username")}: ${it.getString("text").take(120)}" } }) { _,index ->
                            val post = array.getJSONObject(index)
                            val reviewBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),0,dp(20),0) }
                            reviewBody.addView(label(post.getString("text")))
                            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                            val url = post.optString("gif_url")
                            if (ForumGifLoader.allowed(url)) {
                                reviewBody.addView(image,LinearLayout.LayoutParams(-1,dp(200)))
                                gifs.load(image,url,post.optString("gif_hash"))
                            }
                            val review = AlertDialog.Builder(this).setTitle("Review post").setView(reviewBody)
                                .setPositiveButton("Remove + strike") { _,_ -> action({ ForumClient.review(it,post.getString("id"),true) },"Post removed and violation applied.") }
                                .setNeutralButton("Keep post") { _,_ -> action({ ForumClient.review(it,post.getString("id"),false) },"Report reviewed.") }
                                .setNegativeButton("Cancel",null).create()
                            review.setOnDismissListener { gifs.clear(image) }
                            review.show()
                        }.setNegativeButton("Close",null).show()
                },onFailure = { message("Could not load moderation reports.") })
            }
        }
    }
    private fun appeal() {
        val name = EditText(this).apply { hint = "Username"; isSingleLine = true; filters = arrayOf(InputFilter.LengthFilter(24)) }
        AlertDialog.Builder(this).setTitle("Clear forum penalties").setView(name)
            .setMessage("Use after reviewing an appeal. This resets violations and removes the forum mute or ban.")
            .setPositiveButton("Clear penalties") { _,_ -> action({ ForumClient.clearPenalty(it,name.text.toString()) },"Forum penalties cleared.") }
            .setNegativeButton("Cancel",null).show()
    }

    private inner class PostsAdapter : BaseAdapter() {
        override fun getCount() = posts.size
        override fun getItem(position: Int) = posts[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@ForumActivity).inflate(R.layout.forum_post,parent,false).also { card ->
                card.findViewById<ImageView>(R.id.postGif).addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { if (foreground) ((v as ImageView).drawable as? AnimatedImageDrawable)?.start() }
                    override fun onViewDetachedFromWindow(v: View) { ((v as ImageView).drawable as? AnimatedImageDrawable)?.stop() }
                })
            }
            val post = posts[position]
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dp(22).toFloat()
                setColor(Appearance.color(this@ForumActivity,R.attr.rcPanelBg))
                setStroke(dp(1),Appearance.color(this@ForumActivity,if (post.highlighted) R.attr.rcAccent else R.attr.rcOutline))
            }
            view.findViewById<TextView>(R.id.postAuthor).text = post.username
            view.findViewById<TextView>(R.id.postDate).text = date(post.createdAt)
            view.findViewById<TextView>(R.id.postBody).apply { text = post.text; visibility = if (post.text.isEmpty()) View.GONE else View.VISIBLE }
            val image = view.findViewById<ImageView>(R.id.postGif)
            gifs.clear(image)
            image.visibility = if (post.gifUrl != null) View.VISIBLE else View.GONE
            if (post.gifUrl != null && post.gifHash != null) gifs.load(image,post.gifUrl,post.gifHash)
            listOf(Triple(R.id.postLike,"like","👍"),Triple(R.id.postLove,"love","♡"),Triple(R.id.postCelebrate,"celebrate","✦")).forEach { (id,key,icon) ->
                view.findViewById<Button>(id).apply {
                    text = "$icon ${post.reactions[key] ?: 0}"
                    contentDescription = "$key: ${post.reactions[key] ?: 0}" + if (post.mine == key) ", selected" else ""
                    isSelected = post.mine == key
                    setTextColor(Appearance.color(this@ForumActivity,if (post.mine == key) R.attr.rcAccent else R.attr.rcTextSecondary))
                    isEnabled = !restricted(page?.viewer) && !writing
                    setOnClickListener { action({ ForumClient.react(it,post.id,if (post.mine == key) null else key) },"Reaction updated.") }
                }
            }
            view.findViewById<View>(R.id.postMore).setOnClickListener { anchor ->
                PopupMenu(this@ForumActivity,anchor).apply {
                    if (post.own) menu.add("Delete my post").setOnMenuItemClickListener { action({ ForumClient.remove(it,post.id) },"Post deleted."); true }
                    else {
                        menu.add("Report post").setOnMenuItemClickListener {
                            val choices = arrayOf("Harassment","Hate","Sexual content","Graphic violence","Spam","Other")
                            val reasons = arrayOf("harassment","hate","sexual","violence","spam","other")
                            AlertDialog.Builder(this@ForumActivity).setTitle("Report post").setItems(choices) { _,which ->
                                action({ ForumClient.report(it,post.id,reasons[which]) },"Report sent for review.")
                            }.setNegativeButton("Cancel",null).show(); true
                        }
                        menu.add("Block ${post.username}").setOnMenuItemClickListener { action({ ForumClient.block(it,post.username,true) },"Account blocked. Its posts are hidden from your feed."); true }
                    }
                    show()
                }
            }
            return view
        }
    }
}
