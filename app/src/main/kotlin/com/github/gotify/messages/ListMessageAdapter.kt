package com.github.gotify.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateUtils
import android.text.TextUtils
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import coil.ImageLoader
import coil.load
import com.github.gotify.MarkwonFactory
import com.github.gotify.R
import com.github.gotify.Settings
import com.github.gotify.Utils
import com.github.gotify.client.model.Message
import com.github.gotify.databinding.MessageItemBinding
import com.github.gotify.databinding.MessageItemCompactBinding
import com.github.gotify.messages.provider.MessageWithImage
import io.noties.markwon.Markwon
import java.text.DateFormat
import java.util.Date
import org.threeten.bp.OffsetDateTime

internal class ListMessageAdapter(
    private val context: Context,
    private val settings: Settings,
    private val imageLoader: ImageLoader,
    private val delete: Delete
) : ListAdapter<MessageWithImage, ListMessageAdapter.ViewHolder>(DiffCallback) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val markwon: Markwon = MarkwonFactory.createForMessage(context, imageLoader)

    private val timeFormatRelative =
        context.resources.getString(R.string.time_format_value_relative)
    private val timeFormatPrefsKey = context.resources.getString(R.string.setting_key_time_format)

    private var messageLayout = 0

    init {
        val messageLayoutPrefsKey = context.resources.getString(R.string.setting_key_message_layout)
        val messageLayoutNormal = context.resources.getString(R.string.message_layout_value_normal)
        val messageLayoutSetting = prefs.getString(messageLayoutPrefsKey, messageLayoutNormal)

        messageLayout = if (messageLayoutSetting == messageLayoutNormal) {
            R.layout.message_item
        } else {
            R.layout.message_item_compact
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return if (messageLayout == R.layout.message_item) {
            val binding = MessageItemBinding.inflate(layoutInflater, parent, false)
            ViewHolder(binding)
        } else {
            val binding = MessageItemCompactBinding.inflate(layoutInflater, parent, false)
            ViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = currentList[position]
        val isMarkdown = Extras.useMarkdown(message.message)
        
        if (isMarkdown) {
            holder.message.autoLinkMask = 0
            markwon.setMarkdown(holder.message, message.message.message)
        } else {
            holder.message.autoLinkMask = Linkify.WEB_URLS
            holder.message.text = message.message.message
        }
        
        // 设置消息可展开/折叠功能，最近一条消息（position=0）不受折叠限制
        val isRecentMessage = position == 0
        holder.setupExpandableText(message.message.message, isMarkdown, isRecentMessage)
        
        holder.title.text = message.message.title
        if (message.image != null) {
            val url = Utils.resolveAbsoluteUrl("${settings.url}/", message.image)
            holder.image.load(url, imageLoader) {
                error(R.drawable.ic_alarm)
                placeholder(R.drawable.ic_placeholder)
            }
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val timeFormat = prefs.getString(timeFormatPrefsKey, timeFormatRelative)
        holder.setDateTime(message.message.date, timeFormat == timeFormatRelative)
        holder.date.setOnClickListener { holder.switchTimeFormat() }

        holder.delete.setOnClickListener {
            delete.delete(message.message)
        }
    }

    override fun getItemId(position: Int): Long {
        val currentItem = currentList[position]
        return currentItem.message.id
    }

    // Fix for message not being selectable (https://issuetracker.google.com/issues/37095917)
    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.message.isEnabled = false
        holder.message.isEnabled = true
    }

    class ViewHolder(binding: ViewBinding) : RecyclerView.ViewHolder(binding.root) {
        lateinit var image: ImageView
        lateinit var message: TextView
        lateinit var title: TextView
        lateinit var date: TextView
        lateinit var delete: ImageButton
        lateinit var expandButton: MaterialButton

        private var relativeTimeFormat = true
        private lateinit var dateTime: OffsetDateTime
        private var isExpanded = false
        private var fullText: String? = null
        // 与左侧频道图标高度一致 (120dp)
        private val MAX_HEIGHT_COLLAPSED = dpToPx(300)
        
        private fun dpToPx(dp: Int): Int {
            val density = itemView.resources.displayMetrics.density
            return Math.round(dp * density)
        }
        
        fun setupExpandableText(text: String, isMarkdown: Boolean, isRecentMessage: Boolean) {
            fullText = text
            message.maxLines = Integer.MAX_VALUE
            message.post {
                val measuredHeight = message.measuredHeight
                val containsImage = containsImage(text, isMarkdown)
                
                // 最近一条消息不受折叠限制
                if (isRecentMessage) {
                    message.maxHeight = Integer.MAX_VALUE
                    message.ellipsize = null
                    expandButton.visibility = View.GONE
                    isExpanded = true
                } else if (measuredHeight > MAX_HEIGHT_COLLAPSED || containsImage) {
                    // 设置最大高度而不是最大行数
                    message.maxHeight = MAX_HEIGHT_COLLAPSED
                    message.ellipsize = TextUtils.TruncateAt.END
                    expandButton.visibility = View.VISIBLE
                    expandButton.text = itemView.context.getString(R.string.expand_message)
                    isExpanded = false
                } else {
                    expandButton.visibility = View.GONE
                }
            }
            
            expandButton.setOnClickListener {
                toggleExpandState()
            }
        }
        
        /**
         * 检测消息内容中是否包含图片
         * @param text 消息文本内容
         * @param isMarkdown 是否为Markdown格式
         * @return 是否包含图片
         */
        private fun containsImage(text: String, isMarkdown: Boolean): Boolean {
            if (!isMarkdown) {
                return false
            }
            
            // 检查Markdown格式的图片标签 ![alt](url)
            val markdownImagePattern = Regex("""!\[.*?\]\(.*?\)""")
            return markdownImagePattern.containsMatchIn(text)
        }
        
        private fun toggleExpandState() {
            isExpanded = !isExpanded
            
            if (isExpanded) {
                message.maxHeight = Integer.MAX_VALUE
                message.ellipsize = null
                expandButton.text = itemView.context.getString(R.string.collapse_message)
            } else {
                message.maxHeight = MAX_HEIGHT_COLLAPSED
                message.ellipsize = TextUtils.TruncateAt.END
                expandButton.text = itemView.context.getString(R.string.expand_message)
            }
        }

        init {
            enableCopyToClipboard()
            if (binding is MessageItemBinding) {
                image = binding.messageImage
                message = binding.messageText
                title = binding.messageTitle
                date = binding.messageDate
                delete = binding.messageDelete
                expandButton = binding.messageExpandButton
            } else if (binding is MessageItemCompactBinding) {
                image = binding.messageImage
                message = binding.messageText
                title = binding.messageTitle
                date = binding.messageDate
                delete = binding.messageDelete
                expandButton = binding.messageExpandButton
            }
        }

        fun switchTimeFormat() {
            relativeTimeFormat = !relativeTimeFormat
            updateDate()
        }

        fun setDateTime(dateTime: OffsetDateTime, relativeTimeFormatPreference: Boolean) {
            this.dateTime = dateTime
            relativeTimeFormat = relativeTimeFormatPreference
            updateDate()
        }

        private fun updateDate() {
            val text = if (relativeTimeFormat) {
                // Relative time format
                Utils.dateToRelative(dateTime)
            } else {
                // Absolute time format
                val time = dateTime.toInstant().toEpochMilli()
                val date = Date(time)
                if (DateUtils.isToday(time)) {
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
                } else {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)
                }
            }

            date.text = text
        }

        private fun enableCopyToClipboard() {
            super.itemView.setOnLongClickListener { view: View ->
                val clipboard = view.context
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                val clip = ClipData.newPlainText("GotifyMessageContent", message.text.toString())
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        view.context,
                        view.context.getString(R.string.message_copied_to_clipboard),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                true
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<MessageWithImage>() {
        override fun areItemsTheSame(
            oldItem: MessageWithImage,
            newItem: MessageWithImage
        ): Boolean {
            return oldItem.message.id == newItem.message.id
        }

        override fun areContentsTheSame(
            oldItem: MessageWithImage,
            newItem: MessageWithImage
        ): Boolean {
            return oldItem == newItem
        }
    }

    fun interface Delete {
        fun delete(message: Message)
    }
}
