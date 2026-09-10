package kira.ditto.browser

import org.json.JSONObject

/**
 * How much a browser action can change in the world, and therefore who has to agree to it.
 *
 * The rule this encodes is the one that was previously only written in the subagent's profile: "do
 * not submit captchas or payments". A profile is a request to a model, not a gate - it holds right
 * up until the model reads a page that argues otherwise, which is exactly the situation the
 * `<untrusted-web-content>` envelope exists to warn about. Moving the strongest tier into host code
 * makes it a property of the system rather than of the model's compliance.
 */
enum class BrowserAuthorization {
    /** Reads. Nothing observable changes on the far side. */
    Read,

    /** Navigation and tab management: visible, reversible, and how browsing works. */
    Navigate,

    /**
     * Non-idempotent changes: submitting a form, posting, changing a setting.
     *
     * Allowed, because refusing them would make the agent unable to do the tasks it exists for, but
     * marked in the tool result so the model has to account for having made a change - and so the
     * audit trail says one happened.
     */
    Mutate,

    /**
     * Never automatic, whatever the page or the prompt says.
     *
     * Payment, credential entry, and identity checks. These are the actions where being wrong is
     * unrecoverable and where a page that wants to manipulate the agent has the most to gain.
     */
    Never,
}

/** What the classifier saw, so a caller can explain the decision rather than just enforce it. */
data class BrowserAuthorizationVerdict(
    val level: BrowserAuthorization,
    val reason: String = "",
)

private val ReadTools = setOf(
    "browser_capabilities", "browser_tasks", "browser_events",
    "tabs_list", "network_recent", "resources_list", "history_search", "webmcp_list",
    "page_recall", "page_graph", "page_read", "page_snapshot", "page_inspect", "page_settle",
    "page_screenshot", "search_web", "search_images", "browser_fetch_many", "page_wait",
)

private val NavigateTools = setOf(
    "browser_open", "tabs_navigate", "tabs_manage", "browser_find_signup", "page_scroll",
)

private val MutateTools = setOf(
    "browser_execute",
    "page_form", "page_click", "page_type", "page_select", "page_dialog", "page_js", "page_upload",
)

/**
 * Words that mean money is about to move, or a secret is about to be typed.
 *
 * Matched against the control's own label and the page URL, in both languages the agent works in.
 * Deliberately broad: a false positive costs a handoff to the user, a false negative costs a
 * payment made by a model that read a persuasive page.
 */
private val NeverPatterns = listOf(
    "支付", "付款", "结算", "下单", "购买", "充值", "提现", "转账", "银行卡", "绑卡",
    "验证码", "短信验证", "人机验证", "实名", "身份证",
    "pay", "payment", "checkout", "purchase", "billing", "withdraw", "transfer",
    "captcha", "verification code", "otp", "one-time",
)

private val NeverPathPattern = Regex(
    "(?i)/(pay|payment|checkout|billing|order/confirm|wallet|withdraw|transfer)(/|$|\\?)",
)

/** Field kinds that must never be filled by the agent, whatever the surrounding task is. */
private val NeverFieldKinds = setOf("password", "otp", "cvv", "cvc", "card", "cardnumber")

/**
 * Decide what a browser tool call amounts to.
 *
 * Pure so it can be tested exhaustively, and so the same judgement can be reused for the audit line
 * and for the gate without the two drifting apart.
 *
 * @param currentUrl the page the action would run against, when known
 */
fun classifyBrowserAction(
    tool: String,
    arguments: JSONObject,
    currentUrl: String = "",
): BrowserAuthorizationVerdict {
    val normalized = tool.trim().lowercase()

    val submitting = normalized == "page_form" &&
        (arguments.optBoolean("submit") || arguments.optString("action").equals("submit", true))
    val fields = arguments.optJSONArray("fields")
    val hasForbiddenField = (0 until (fields?.length() ?: 0)).any { index ->
        val field = fields?.optJSONObject(index) ?: return@any false
        val kind = field.optString("kind").trim().lowercase()
        val name = field.optString("name").trim().lowercase()
        kind in NeverFieldKinds || NeverFieldKinds.any { it in name }
    }
    if (hasForbiddenField) {
        return BrowserAuthorizationVerdict(
            BrowserAuthorization.Never,
            "a credential or card field would be filled",
        )
    }

    val haystack = buildString {
        append(currentUrl.lowercase()).append(' ')
        append(arguments.optString("url").lowercase()).append(' ')
        append(arguments.optString("label").lowercase()).append(' ')
        append(arguments.optString("text").lowercase()).append(' ')
        append(arguments.optString("ref").lowercase())
    }
    val actionable = normalized in MutateTools || submitting
    if (actionable) {
        NeverPatterns.firstOrNull { it in haystack }?.let { hit ->
            return BrowserAuthorizationVerdict(
                BrowserAuthorization.Never,
                "the target mentions \"$hit\"",
            )
        }
        if (NeverPathPattern.containsMatchIn(currentUrl) ||
            NeverPathPattern.containsMatchIn(arguments.optString("url"))
        ) {
            return BrowserAuthorizationVerdict(
                BrowserAuthorization.Never,
                "the page is a payment or checkout path",
            )
        }
    }

    return when {
        normalized in ReadTools -> BrowserAuthorizationVerdict(BrowserAuthorization.Read)
        normalized in NavigateTools -> BrowserAuthorizationVerdict(BrowserAuthorization.Navigate)
        normalized in MutateTools -> BrowserAuthorizationVerdict(
            BrowserAuthorization.Mutate,
            if (submitting) "submits a form" else "changes page state",
        )
        // An unrecognised tool is treated as a change, not as a read. A new tool added later should
        // have to be classified deliberately rather than default into the permissive tier.
        else -> BrowserAuthorizationVerdict(BrowserAuthorization.Mutate, "unclassified tool")
    }
}

/**
 * The refusal a [BrowserAuthorization.Never] action gets, shaped like the existing login-wall stop.
 *
 * Reuses `input_required` + `user_takeover` because that path already exists and already does the
 * right thing: it stops the agent and hands the browser card to the person, who can finish the step
 * themselves. Inventing a second stop mechanism would mean two things to keep correct.
 */
fun browserAuthorizationRefusal(verdict: BrowserAuthorizationVerdict, tool: String): JSONObject =
    JSONObject()
        .put("ok", false)
        .put("code", "input_required")
        .put("user_takeover", true)
        .put("pause_code", "authorization")
        .put(
            "reason",
            "This step is not something the agent may do on its own: $tool - " +
                verdict.reason.ifBlank { "it is in the never-automatic tier" } +
                ". Tell the user what remains and let them finish it in the browser card.",
        )
