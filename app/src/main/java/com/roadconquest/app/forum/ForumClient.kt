package com.roadconquest.app.forum

import android.net.Uri
import com.roadconquest.app.account.AccountClient
import org.json.JSONObject

object ForumClient {
    const val PRODUCT_ID = "forum_plus_lifetime"
    const val RULES_VERSION = 1
    data class Viewer(val username: String, val strikes: Int, val mutedUntil: String?, val banned: Boolean,
        val plus: Boolean, val billingAccountId: String, val moderator: Boolean, val nextPostAt: String? = null)
    data class Post(val id: String, val username: String, val text: String, val gifUrl: String?, val gifHash: String?,
        val createdAt: String, val highlighted: Boolean, val own: Boolean, val reactions: Map<String, Int>, val mine: String?)
    data class Page(val available: Boolean, val posting: Boolean, val purchases: Boolean, val posts: List<Post>,
        val next: String?, val viewer: Viewer?)

    private fun JSONObject.optional(name: String): String? = optString(name).takeUnless { it.isBlank() || it == "null" }

    fun posts(token: String?, before: String? = null): Page {
        val path = "posts" + if (before == null) "" else "?before=" + Uri.encode(before)
        val body = AccountClient.forumRequest(path,"GET",null,token)
        return parsePage(body)
    }
    internal fun parsePage(body: JSONObject): Page {
        val array = body.optJSONArray("posts")
        require((array?.length() ?: 0) <= 20) { "Community page is too large." }
        val posts = (0 until (array?.length() ?: 0)).map { index ->
            val p = array!!.getJSONObject(index)
            val counts = p.getJSONObject("reactions")
            Post(p.getString("id"),p.getString("username"),p.getString("text"),p.optional("gif_url"),p.optional("gif_hash"),
                p.getString("created_at"),p.optBoolean("highlighted"),p.optBoolean("own"),
                listOf("like","love","celebrate").associateWith { counts.optInt(it).coerceAtLeast(0) },p.optional("my_reaction"))
        }
        val viewer = body.optJSONObject("viewer")?.let { v ->
            Viewer(v.getString("username"),v.optInt("violations"),v.optional("muted_until"),v.optBoolean("banned"),
                v.optBoolean("plus"),v.getString("billing_account_id"),v.optBoolean("moderator"),v.optional("next_post_at"))
        }
        return Page(body.optBoolean("available"),body.optBoolean("posting_available"),body.optBoolean("purchases_available"),posts,body.optional("next"),viewer)
    }

    fun post(token: String, text: String, gifUrl: String?, requestId: String): JSONObject =
        AccountClient.forumRequest("posts","POST",JSONObject().put("text",text)
            .put("gif_url",gifUrl ?: JSONObject.NULL).put("request_id",requestId).put("rules_version",RULES_VERSION),token)

    fun react(token: String, id: String, reaction: String?) = AccountClient.forumRequest("posts/${Uri.encode(id)}/reaction",
        "PUT",JSONObject().put("reaction",reaction ?: JSONObject.NULL),token)

    fun remove(token: String, id: String) = AccountClient.forumRequest("posts/${Uri.encode(id)}","DELETE",null,token)
    fun report(token: String, id: String, reason: String) = AccountClient.forumRequest("posts/${Uri.encode(id)}/report",
        "POST",JSONObject().put("reason",reason),token)
    fun block(token: String, username: String, enabled: Boolean) = AccountClient.forumRequest("blocks","PUT",
        JSONObject().put("username",username).put("blocked",enabled),token)
    fun blocks(token: String) = AccountClient.forumRequest("blocks","GET",null,token)
    fun purchase(token: String, purchaseToken: String) = AccountClient.forumRequest("purchase","POST",
        JSONObject().put("purchase_token",purchaseToken),token)
    fun reports(token: String) = AccountClient.forumRequest("reports","GET",null,token)
    fun review(token: String, id: String, remove: Boolean) = AccountClient.forumRequest("review","POST",
        JSONObject().put("post_id",id).put("remove",remove),token)
    fun clearPenalty(token: String, username: String) = AccountClient.forumRequest("appeal-review","POST",
        JSONObject().put("username",username),token)
}
