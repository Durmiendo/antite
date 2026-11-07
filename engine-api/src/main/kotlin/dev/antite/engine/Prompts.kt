package dev.antite.engine

import android.net.Uri

sealed interface Prompt {
    fun dismiss()

    class Alert(val title: String?, val message: String?, private val done: () -> Unit) : Prompt {
        override fun dismiss() = done()
    }

    class Confirm(
        val title: String?,
        val message: String?,
        private val answer: (Boolean?) -> Unit,
    ) : Prompt {
        fun confirm(positive: Boolean) = answer(positive)

        override fun dismiss() = answer(null)
    }

    class Text(
        val title: String?,
        val message: String?,
        val defaultValue: String?,
        private val answer: (String?) -> Unit,
    ) : Prompt {
        fun confirm(text: String) = answer(text)

        override fun dismiss() = answer(null)
    }

    class Auth(
        val title: String?,
        val message: String?,
        val username: String?,
        val onlyPassword: Boolean,
        private val answer: (Pair<String, String>?) -> Unit,
    ) : Prompt {
        fun confirm(username: String, password: String) = answer(username to password)

        override fun dismiss() = answer(null)
    }

    data class Choice(val id: String, val label: String, val selected: Boolean, val enabled: Boolean)

    class Choices(
        val title: String?,
        val message: String?,
        val multiple: Boolean,
        val choices: List<Choice>,
        private val answer: (List<String>?) -> Unit,
    ) : Prompt {
        fun confirm(ids: List<String>) = answer(ids)

        override fun dismiss() = answer(null)
    }

    enum class DateKind { DATE, TIME, DATETIME, MONTH, WEEK }

    class DateTime(
        val kind: DateKind,
        val value: String?,
        val min: String?,
        val max: String?,
        private val answer: (String?) -> Unit,
    ) : Prompt {
        fun confirm(value: String) = answer(value)

        override fun dismiss() = answer(null)
    }

    class Color(val value: String?, private val answer: (String?) -> Unit) : Prompt {
        fun confirm(value: String) = answer(value)

        override fun dismiss() = answer(null)
    }

    class File(
        val mimeTypes: List<String>,
        val multiple: Boolean,
        private val answer: (List<Uri>?) -> Unit,
    ) : Prompt {
        fun confirm(uris: List<Uri>) = answer(uris)

        override fun dismiss() = answer(null)
    }

    class Popup(val targetUrl: String?, private val answer: (Boolean) -> Unit) : Prompt {
        fun allow() = answer(true)

        override fun dismiss() = answer(false)
    }

    class BeforeUnload(private val answer: (Boolean) -> Unit) : Prompt {
        fun leave() = answer(true)

        override fun dismiss() = answer(false)
    }

    class Repost(private val answer: (Boolean) -> Unit) : Prompt {
        fun resend() = answer(true)

        override fun dismiss() = answer(false)
    }

    class SaveLogin(val login: Login, private val answer: (Boolean) -> Unit) : Prompt {
        fun save() = answer(true)

        override fun dismiss() = answer(false)
    }

    data class LoginOption(val login: Login, val generated: Boolean)

    class SelectLogin(val options: List<LoginOption>, private val answer: (Int?) -> Unit) : Prompt {
        fun select(index: Int) = answer(index)

        override fun dismiss() = answer(null)
    }

    class SaveCard(val card: Card, private val answer: (Boolean) -> Unit) : Prompt {
        fun save() = answer(true)

        override fun dismiss() = answer(false)
    }

    class SelectCard(val cards: List<Card>, private val answer: (Int?) -> Unit) : Prompt {
        fun select(index: Int) = answer(index)

        override fun dismiss() = answer(null)
    }

    class SaveAddress(val address: Address, private val answer: (Boolean) -> Unit) : Prompt {
        fun save() = answer(true)

        override fun dismiss() = answer(false)
    }

    class SelectAddress(val addresses: List<Address>, private val answer: (Int?) -> Unit) : Prompt {
        fun select(index: Int) = answer(index)

        override fun dismiss() = answer(null)
    }
}
