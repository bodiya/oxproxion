package io.github.stardomains3.oxproxion

import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.os.StrictMode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.noties.markwon.Markwon
import io.noties.markwon.utils.NoCopySpannableFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

class ChatAdapter(
    private val scope: CoroutineScope,
    private val markwon: Markwon,
    private val onSpeakText: (String, Int) -> Unit,
    private val onSynthesizeToWavFile: (String, Int) -> Unit,
    private val ttsAvailable: Boolean,
    private val onEditMessage: (Int, String) -> Unit,
    private val onRedoMessage: (Int, JsonElement) -> Unit,
    private val onDeleteMessage: (Int) -> Unit,
    private val onEditAssistantMessage: (Int, String) -> Unit,
    private val onSaveMarkdown: (Int, String) -> Unit,
    private val onCaptureItemToBitmap: (Int, String) -> Unit,
    private val onShowMarkdown: (String) -> Unit,
    private val onSaveHtml: (String) -> Unit,
    private val onSaveText: (Int, String) -> Unit,
    private val onCollapse: () -> Unit,
    private val onSaveAsFile: (String) -> Unit,
    private val onPreviewHtml: (String) -> Unit

) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // --- STATE & CACHE ---
    // Changed to Map to use stable keys (content hash) instead of unstable positions
    private val collapsedStates = mutableMapOf<String, Boolean>()
    // The "Baked" Cache for Markdown CharSequences
    private val renderCache = HashMap<FlexibleMessage, CharSequence>()

    private val noCopyFactory = NoCopySpannableFactory.getInstance()
    var isSpeaking = false
    var currentSpeakingPosition = -1
    private var currentTypeface: Typeface = Typeface.DEFAULT

    // OPTIMIZATION: Conflated Channel for throttling updates
    private val updateChannel = Channel<FlexibleMessage>(Channel.CONFLATED)
    private val messages = mutableListOf<FlexibleMessage>()
    private var isUserApplyingEdit: Boolean = false
    private var editTargetPosition: Int = -1
    private var currentFontScale: Int = 100
    init {
        // OPTIMIZATION: Consumer loop
        scope.launch(Dispatchers.Main) {
            for (newMessage in updateChannel) {
                if (messages.isNotEmpty()) {
                    messages[messages.size - 1] = newMessage
                    // Send "STREAMING" payload to update ONLY text (avoids full re-bind)
                    notifyItemChanged(messages.size - 1, "STREAMING")
                }
                // Throttle updates to ~20fps (50ms)
                delay(50)
            }
        }
    }

    // --- PUBLIC METHODS ---

    fun clearCache() {
        renderCache.clear()
        collapsedStates.clear()
    }
    fun getLatestPlainText(): String? {
        return messages.lastOrNull()?.let { getMessageText(it.content) }
    }

    fun updateTtsState(speaking: Boolean, position: Int) {
        isSpeaking = speaking
        currentSpeakingPosition = position
    }

    fun updateFont(newTypeface: Typeface?) {
        currentTypeface = newTypeface ?: Typeface.DEFAULT
        notifyDataSetChanged()
    }

    fun finalizeStreaming() {
        scope.launch(Dispatchers.Main) {
            delay(100)
            if (messages.isNotEmpty()) {
                val lastIndex = messages.size - 1
                val message = messages[lastIndex]

                // OPTIMIZATION:
                // 1. Force the heavy calculation (Regex + Markdown) NOW.
                // This populates the renderCache[message] with the fixed table spacing.
                getPreRenderedContent(message)

                // 2. Notify the view.
                // When onBindViewHolder runs, it will call getPreRenderedContent,
                // find the data we just cached, and skip the heavy work.
                notifyItemChanged(lastIndex)
            }
        }
    }

    fun setMessages(newMessages: List<FlexibleMessage>) {
        if (isUserApplyingEdit) {
            applyEditUpdate(newMessages)
            return // Stop here, don't run the rest
        }
        // Clear cache if loading a fresh list or switching chats
        if (newMessages.isEmpty() || (messages.isEmpty() && newMessages.isNotEmpty())) {
            renderCache.clear()
        }

        if (newMessages.isEmpty()) {
            messages.clear()
            notifyDataSetChanged()
            return
        }

        // PERFECT CASE: Only 1 new message added
        if (messages.size == newMessages.size - 1 &&
            messages == newMessages.dropLast(1)) {
            addMessage(newMessages.last())
            return
        }

        // STREAMING CASE: Same size, only last message content changed
        if (messages.size == newMessages.size &&
            messages.dropLast(1) == newMessages.dropLast(1)) {
            updateLastMessage(newMessages.last())
            return
        }

        // Fallback: Full refresh
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun addMessage(message: FlexibleMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
    private fun applyEditUpdate(newMessages: List<FlexibleMessage>) {
        // 3. Use the stored position directly (Fast!)
        val index = editTargetPosition
        // Safety check: ensure index is valid
        if (index != -1 && index < messages.size && index < newMessages.size) {
            val oldMsg = messages[index]

            // 4. Clear cache
            renderCache.remove(oldMsg)

            // 5. Update list
            messages[index] = newMessages[index]

            // 6. Notify
            notifyItemChanged(index)
        }
        // 7. Reset BOTH flags
        isUserApplyingEdit = false
        editTargetPosition = -1
    }
    fun removeLastMessage() {
        if (messages.isNotEmpty()) {
            val lastIndex = messages.size - 1
            messages.removeAt(lastIndex)
            notifyItemRemoved(lastIndex)
        }
    }

    fun updateLastMessage(newMessage: FlexibleMessage) {
        if (messages.isNotEmpty()) {
            val oldMessage = messages.last()
            renderCache.remove(oldMessage) // Invalidate cache for the streaming message
        }
        updateChannel.trySend(newMessage)
    }

    // --- DATA HELPERS ---
    fun flagEditUpdate(position: Int) {
        isUserApplyingEdit = true
        editTargetPosition = position
    }
    fun updateFontSize(scalePercent: Int) {
        currentFontScale = scalePercent.coerceIn(50, 200) // clamp 50%-200%
        notifyDataSetChanged()
    }
    private fun getMessageText(content: JsonElement): String {
        if (content is JsonPrimitive) return content.content
        if (content is JsonArray) {
            return content.firstNotNullOfOrNull { item ->
                (item as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }?.get("text")?.jsonPrimitive?.content
            } ?: ""
        }
        return ""
    }

    private fun getImageBase64(content: JsonElement): String? {
        if (content is JsonArray) {
            return content.firstNotNullOfOrNull { item ->
                (item as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "image_url" }?.get("image_url")?.jsonObject?.get("url")?.jsonPrimitive?.content?.substringAfter(",")
            }
        }
        return null
    }

    // --- OPTIMIZED BAKING FUNCTION ---
    private fun getPreRenderedContent(message: FlexibleMessage): CharSequence {
        // 1. Check Cache
        if (renderCache.containsKey(message)) {
            return renderCache[message]!!
        }

        // 2. Extract Text (JSON Logic)
        val text = if (message.role == "assistant" && message.toolCalls != null && getMessageText(message.content).isBlank()) {
            // Show a clean, formatted indicator of what tool was used
            "🔧 **Tool Used:** ${message.toolCalls.map { it.function.name }.distinct().joinToString()}"
        } else {
            getMessageText(message.content)
        }

        val reasoningText = message.reasoning?.let { "\n\n$it" } ?: ""
        val rawText = reasoningText + text

        // 3. Run Regex (Expensive)
        val fullText = ensureTableSpacing(rawText)

        // 4. Render Markdown with Safety (Expensive)
        val renderedContent = try {
            markwon.toMarkdown(fullText)
        } catch (e: RuntimeException) {
            // 5. Prism4j Crash Handler
            if (e.message?.contains("Prism4j") == true || e.message?.contains("entry nodes") == true) {
                fullText // Fallback: Return the plain text
            } else {
                throw e
            }
        }

        // 6. Save to Cache
        renderCache[message] = renderedContent

        return renderedContent
    }

    private fun ensureTableSpacing(md: String): String {
        val pattern = Regex(
            """(^[\t >]*([-+*]|\d+\.)\s+(?:\\\$\\\[ ?[ xX]?\\]\\\s+)?[^\n]*)\n(?=\|)""",
            RegexOption.MULTILINE
        )
        return md.replace(pattern) { "${it.value}\n\n" }
    }

    // --- VIEW HOLDER LOGIC ---

    companion object {
        const val VIEW_TYPE_USER = 1
        const val VIEW_TYPE_ASSISTANT = 2
        const val VIEW_TYPE_THINKING = 3
        const val VIEW_TYPE_HIDDEN = 4

        // Export dropdown menu item ids
        private const val EXPORT_PDF = 1
        private const val EXPORT_MARKDOWN = 2
        private const val EXPORT_TEXT = 3
        private const val EXPORT_PNG = 4
        private const val EXPORT_JPG = 5
        private const val EXPORT_WEBP = 6
        private const val EXPORT_VIEW_HTML = 7
        private const val EXPORT_SAVE_HTML = 8
        private const val EXPORT_SAVE_FILE = 9
        private const val EXPORT_PREVIEW_HTML_BLOCK = 10
    }

    override fun getItemViewType(position: Int): Int {
        val message = messages[position]

        // 1. ONLY hide the raw tool results (the giant data dump)
        if (message.role == "tool") return VIEW_TYPE_HIDDEN

        // 2. Do NOT hide the assistant's tool calls anymore.
        val contentText = getMessageText(message.content)

        return when (message.role) {
            "user" -> VIEW_TYPE_USER
            "assistant" -> {
                if (contentText == "working...") VIEW_TYPE_THINKING else VIEW_TYPE_ASSISTANT
            }
            else -> VIEW_TYPE_ASSISTANT
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HIDDEN -> { // <--- ADD THIS BLOCK
                val emptyView = View(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(0, 0)
                    visibility = View.GONE
                }
                HiddenViewHolder(emptyView)
            }
            VIEW_TYPE_USER -> {
                val view = inflater.inflate(R.layout.item_message_user, parent, false)
                view.findViewById<TextView>(R.id.messageTextView)
                    .setSpannableFactory(noCopyFactory)
                UserViewHolder(view, markwon)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_message_ai, parent, false)
                view.findViewById<TextView>(R.id.messageTextView)
                    .setSpannableFactory(noCopyFactory)
                AssistantViewHolder(view, markwon, onSpeakText, onSynthesizeToWavFile)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            if (payloads.first() == "STREAMING" && holder is AssistantViewHolder) {
                holder.bindTextOnly(messages[position])
                return
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        var contentText = getMessageText(message.content)

        if (message.role == "assistant" && message.toolCalls != null && contentText.isBlank()) {
            contentText = "🔧 **Tool Used:** ${message.toolCalls.map { it.function.name }.distinct().joinToString()}"
        }

        when (holder) {
            is UserViewHolder -> holder.bind(message)
            is AssistantViewHolder -> holder.bind(message, position, isSpeaking, currentSpeakingPosition)
        }
    }

    override fun getItemCount(): Int = messages.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is AssistantViewHolder) holder.stopPulse()
    }

    // --- VIEW HOLDERS ---

    inner class UserViewHolder(itemView: View, private val markwon: Markwon) : RecyclerView.ViewHolder(itemView) {
        private val messageTextView: TextView = itemView.findViewById(R.id.messageTextView)
        private val copyButtonuser: ImageButton = itemView.findViewById(R.id.copyButtonuser)
        private val resendButton: ImageButton = itemView.findViewById(R.id.resendButton)
        private val editButton: ImageButton = itemView.findViewById(R.id.editButton)
        private val imageView: ImageView = itemView.findViewById(R.id.userImageView)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
        private val collapseToggleButton: ImageButton = itemView.findViewById(R.id.collapseToggleButton)

        fun bind(message: FlexibleMessage) {
            messageTextView.textSize = 16f * currentFontScale / 100f
            messageTextView.typeface = currentTypeface
            val rawUserContent = getMessageText(message.content)
            val pos = bindingAdapterPosition
            collapseToggleButton.visibility = View.GONE

            if (pos >= 0 && message.role == "user") {
                val displayMetrics = itemView.resources.displayMetrics
                val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
                val isTablet = screenWidthDp >= 600
                val MAX_CHARS_THRESHOLD = if (isTablet) 300 else 150
                val MAX_LINES_THRESHOLD = 3

                val rawLines = rawUserContent.lines().size
                val charLength = rawUserContent.length
                val isLongMessage = rawLines > MAX_LINES_THRESHOLD || charLength > MAX_CHARS_THRESHOLD

                if (isLongMessage) {
                    // Use stable key (content hash) instead of position
                    val msgKey = rawUserContent.hashCode().toString()
                    val isCollapsed = collapsedStates.getOrDefault(msgKey, true)

                    val displayContent = if (isCollapsed) {
                        if (charLength > MAX_CHARS_THRESHOLD) {
                            val cutOffIndex =
                                rawUserContent.take(MAX_CHARS_THRESHOLD).lastIndexOf(' ')
                            val safeIndex = if (cutOffIndex > 0) cutOffIndex else MAX_CHARS_THRESHOLD
                            rawUserContent.take(safeIndex) + "...(continued)"
                        } else {
                            rawUserContent.lines().take(MAX_LINES_THRESHOLD).joinToString("\n") + "\n\n**...(continued)**"
                        }
                    } else {
                        rawUserContent
                    }

                    try {
                        markwon.setMarkdown(messageTextView, displayContent)
                    } catch (e: RuntimeException) {
                        if (e.message?.contains("Prism4j") == true || e.message?.contains("entry nodes") == true) {
                            messageTextView.text = displayContent
                        } else {
                            throw e
                        }
                    }

                    collapseToggleButton.visibility = View.VISIBLE
                    collapseToggleButton.setImageResource(
                        if (isCollapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less2
                    )
                    collapseToggleButton.setOnClickListener {
                        collapsedStates[msgKey] = !isCollapsed
                        this@ChatAdapter.notifyItemChanged(pos)
                        onCollapse()
                    }
                } else {
                    try {
                        markwon.setMarkdown(messageTextView, rawUserContent)
                    } catch (e: RuntimeException) {
                        if (e.message?.contains("Prism4j") == true || e.message?.contains("entry nodes") == true) {
                            messageTextView.text = rawUserContent
                        } else {
                            throw e
                        }
                    }
                }
            } else {
                try {
                    markwon.setMarkdown(messageTextView, rawUserContent)
                } catch (e: RuntimeException) {
                    if (e.message?.contains("Prism4j") == true || e.message?.contains("entry nodes") == true) {
                        messageTextView.text = rawUserContent
                    } else {
                        throw e
                    }
                }
            }

            // ... (Image and Button logic) ...
            val imageUriStr = message.imageUri
            if (!imageUriStr.isNullOrEmpty()) {
                try {
                    val userImageUri = imageUriStr.toUri()
                    val request = ImageRequest.Builder(itemView.context)
                        .data(userImageUri)
                        .target(imageView)
                        .build()
                    ImageLoader(itemView.context).enqueue(request)
                    imageView.visibility = View.VISIBLE
                    imageView.setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(userImageUri, "image/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            itemView.context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(itemView.context, "Could not open image", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    val base64 = getImageBase64(message.content)
                    if (base64 != null) {
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        imageView.setImageBitmap(bitmap)
                        imageView.visibility = View.VISIBLE
                    } else {
                        imageView.visibility = View.GONE
                    }
                }
            } else {
                imageView.visibility = View.GONE
            }

            copyButtonuser.setOnClickListener {
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Text", rawUserContent)
                clipboard.setPrimaryClip(clip)
            }
            copyButtonuser.setOnLongClickListener {
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Markdown", rawUserContent)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(itemView.context, "Raw Markdown copied to clipboard", Toast.LENGTH_SHORT).show()
                true
            }
            editButton.setOnClickListener {
                if (rawUserContent.isNotBlank()) {
                    onEditMessage(bindingAdapterPosition, rawUserContent)
                }
            }
            resendButton.setOnClickListener {
                onRedoMessage(bindingAdapterPosition, message.content)
            }
            deleteButton.setOnClickListener {
                onDeleteMessage(bindingAdapterPosition)
            }
        }
    }

    inner class AssistantViewHolder(
        itemView: View,
        private val markwon: Markwon,
        private val onSpeakText: (String, Int) -> Unit,
        private val onSynthesizeToWavFile: (String, Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val messageTextView: TextView = itemView.findViewById(R.id.messageTextView)
        private val copyButton: ImageButton = itemView.findViewById(R.id.copyButton)
        private val exportButton: ImageButton = itemView.findViewById(R.id.exportButton)
        private val shareButton: ImageButton = itemView.findViewById(R.id.shareButton)
        val ttsButton: ImageButton = itemView.findViewById(R.id.ttsButton)
        private val generatedImageView: ImageView = itemView.findViewById(R.id.generatedImageView)
        val messageContainer: ConstraintLayout = itemView.findViewById(R.id.messageContainer)
        private var pulseAnimator: ObjectAnimator? = null
        private var bgColorAnimator: ObjectAnimator? = null
        private val collapseToggleButton: ImageButton = itemView.findViewById(R.id.collapseToggleButton)
        private val infoButton: ImageButton = itemView.findViewById(R.id.infoButton)
        private val editButton: ImageButton = itemView.findViewById(R.id.editButton)
        // Configuration for "Long Message" detection
        private val CHAR_THRESHOLD = 350

        fun bindTextOnly(message: FlexibleMessage) {
            val text = getMessageText(message.content)
            val reasoning = message.reasoning
            val displayText = if (!reasoning.isNullOrBlank()) {
                "$reasoning\n\n$text"
            } else {
                text
            }
            messageTextView.text = displayText
        }

        fun bind(message: FlexibleMessage, position: Int, isSpeaking: Boolean, currentPosition: Int) {
            messageTextView.textSize = 16f * currentFontScale / 100f
            messageTextView.typeface = currentTypeface

            // 1. DISPLAY TEXT (Optimized: Uses Cache)
            val finalContent = getPreRenderedContent(message)
            // messageTextView.text = finalContent
            markwon.setParsedMarkdown(messageTextView, finalContent as android.text.Spanned)

            // 2. LOGIC TEXT (Fast extraction)
            val text = if (message.role == "assistant" && message.toolCalls != null && getMessageText(message.content).isBlank()) {
                "Tool Call: ${message.toolCalls.map { it.function.name }.distinct().joinToString()}"
            } else {
                getMessageText(message.content)
            }

            // --- NEW COLLAPSE LOGIC (INSTANT, NO POST DELAY) ---
            if (text.length > CHAR_THRESHOLD) {
                val msgKey = text.hashCode().toString()
                val isCollapsed = collapsedStates.getOrDefault(msgKey, false) // Default Expanded (false)

                applyCollapseState(isCollapsed)

                collapseToggleButton.visibility = View.VISIBLE
                collapseToggleButton.setImageResource(
                    if (isCollapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less2
                )

                collapseToggleButton.setOnClickListener {
                    val newState = !collapsedStates.getOrDefault(msgKey, false)
                    collapsedStates[msgKey] = newState

                    applyCollapseState(newState)
                    collapseToggleButton.setImageResource(
                        if (newState) R.drawable.ic_expand_more else R.drawable.ic_expand_less2
                    )
                    onCollapse()
                }
            } else {
                messageTextView.maxLines = Int.MAX_VALUE
                messageTextView.ellipsize = null
                collapseToggleButton.visibility = View.GONE
                collapseToggleButton.setOnClickListener(null)
            }
            // ---------------------------------------------------

            val reasoningText = message.reasoning?.let { "\n\n$it" } ?: ""

            // 3. UI STATE LOGIC
            ttsButton.visibility = if (ttsAvailable) View.VISIBLE else View.GONE

            val isError = message.role == "assistant" && text.startsWith("**Error:**")
            val isThinking = text == "working..."
            val hasActiveTools = message.toolCalls != null && message.toolCalls.isNotEmpty()

            if (isError) {
                messageContainer.setBackgroundResource(R.drawable.bg_error_message)
            } else if (hasActiveTools) {
                messageContainer.setBackgroundResource(R.drawable.bg_ai_message_outlined)
            } else {
                messageContainer.setBackgroundResource(R.drawable.bg_ai_message)
            }

            // 4. ANIMATIONS
            pulseAnimator?.cancel()
            bgColorAnimator?.cancel()
            pulseAnimator = null
            bgColorAnimator = null

            if (isThinking) {
                val originalDrawable = ContextCompat.getDrawable(itemView.context, R.drawable.bg_ai_message) as GradientDrawable
                val animatedDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0xFF2C2C2C.toInt())
                    cornerRadius = 16f * itemView.resources.displayMetrics.density
                }

                messageContainer.background = animatedDrawable

                val alphaAnimator = ObjectAnimator.ofFloat(messageContainer, "alpha", 0.2f, 1f).apply {
                    duration = 4000
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                }

                val colorAnimator = ObjectAnimator.ofArgb(
                    animatedDrawable,
                    "color",
                    0xFF222f3d.toInt(),
                    0xFF2C2C2C.toInt()
                ).apply {
                    duration = 2000
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                }

                alphaAnimator.start()
                colorAnimator.start()

                pulseAnimator = alphaAnimator
                bgColorAnimator = colorAnimator
            } else {
                messageContainer.alpha = 1f
            }

            // 5. IMAGE LOADING
            val generatedUriStr = message.imageUri
            if (!generatedUriStr.isNullOrEmpty()) {
                try {
                    val generatedUri = generatedUriStr.toUri()
                    val request = ImageRequest.Builder(itemView.context)
                        .data(generatedUri)
                        .target(generatedImageView)
                        .build()
                    ImageLoader(itemView.context).enqueue(request)
                    generatedImageView.visibility = View.VISIBLE

                    generatedImageView.setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(generatedUri, "image/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            itemView.context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(itemView.context, "Could not open image", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    generatedImageView.visibility = View.GONE
                }
            } else {
                generatedImageView.visibility = View.GONE
            }

            // 6. BUTTON LISTENERS (Lazy Calculation)
            exportButton.setOnClickListener { anchor ->
                val fullRawMarkdown = ensureTableSpacing(reasoningText + text)
                val htmlBlocks = HtmlCodeBlocks.extractHtmlBlocks(fullRawMarkdown)
                val popup = PopupMenu(itemView.context, anchor)
                popup.menu.apply {
                    add(0, EXPORT_PDF, 0, "Save as PDF")
                    add(0, EXPORT_MARKDOWN, 1, "Save as Markdown (.md)")
                    add(0, EXPORT_TEXT, 2, "Save as Text (.txt)")
                    add(0, EXPORT_PNG, 3, "Save as PNG")
                    add(0, EXPORT_JPG, 4, "Save as JPG")
                    add(0, EXPORT_WEBP, 5, "Save as WebP")
                    add(0, EXPORT_VIEW_HTML, 6, "View as HTML")
                    if (htmlBlocks.isNotEmpty()) {
                        add(0, EXPORT_PREVIEW_HTML_BLOCK, 7, if (htmlBlocks.size == 1) "Preview HTML code block" else "Preview HTML code block…")
                    }
                    add(0, EXPORT_SAVE_HTML, 8, "Save as HTML")
                    add(0, EXPORT_SAVE_FILE, 9, "Save as file…")
                }
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        EXPORT_PDF -> { exportPdf(message, fullRawMarkdown); true }
                        EXPORT_MARKDOWN -> { onSaveMarkdown(bindingAdapterPosition, fullRawMarkdown); true }
                        EXPORT_TEXT -> { onSaveText(bindingAdapterPosition, messageTextView.text.toString()); true }
                        EXPORT_PNG -> { onCaptureItemToBitmap(bindingAdapterPosition, "png"); true }
                        EXPORT_JPG -> { onCaptureItemToBitmap(bindingAdapterPosition, "jpg"); true }
                        EXPORT_WEBP -> { onCaptureItemToBitmap(bindingAdapterPosition, "webp"); true }
                        EXPORT_VIEW_HTML -> {
                            if (fullRawMarkdown.isNotBlank()) onShowMarkdown.invoke(fullRawMarkdown)
                            true
                        }
                        EXPORT_SAVE_HTML -> {
                            if (fullRawMarkdown.isNotBlank()) onSaveHtml.invoke(fullRawMarkdown)
                            true
                        }
                        EXPORT_SAVE_FILE -> { onSaveAsFile.invoke(text); true }
                        EXPORT_PREVIEW_HTML_BLOCK -> {
                            if (htmlBlocks.size == 1) {
                                onPreviewHtml.invoke(htmlBlocks[0])
                            } else {
                                val labels = htmlBlocks.mapIndexed { index, block ->
                                    "Block ${index + 1} (${block.lines().size} lines)"
                                }.toTypedArray()
                                MaterialAlertDialogBuilder(itemView.context)
                                    .setTitle("Preview HTML code block")
                                    .setItems(labels) { _, which ->
                                        onPreviewHtml.invoke(htmlBlocks[which])
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }

            val infoModel = message.modelUsed
            val infoCost = message.cost
            if ((infoModel != null || infoCost != null) && !isThinking && !isError) {
                infoButton.visibility = View.VISIBLE
                infoButton.setOnClickListener {
                    val info = buildString {
                        append("Model: ${infoModel ?: "Unknown"}")
                        message.provider?.let { append("\nProvider: $it") }
                        message.promptTokens?.let { append("\nPrompt tokens: $it") }
                        message.completionTokens?.let { append("\nCompletion tokens: $it") }
                        message.reasoningTokens?.takeIf { it > 0 }?.let { append("\nReasoning tokens: $it") }
                        append("\nCost: " + (infoCost?.let { c -> String.format(Locale.US, "$%.6f", c) } ?: "Not reported"))
                        val conversationTotal = messages.sumOf { m -> m.cost ?: 0.0 }
                        if (conversationTotal > 0.0) {
                            append("\nConversation so far: " + String.format(Locale.US, "$%.6f", conversationTotal))
                        }
                        message.durationMs?.let { d ->
                            val seconds = d / 1000.0
                            append("\nGeneration time: " + String.format(Locale.US, "%.1fs", seconds))
                            message.completionTokens?.takeIf { seconds > 0.5 }?.let { ct ->
                                append(String.format(Locale.US, " (%.1f tok/s)", ct / seconds))
                            }
                        }
                    }
                    MaterialAlertDialogBuilder(itemView.context)
                        .setTitle("Response Info")
                        .setMessage(info)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } else {
                infoButton.visibility = View.GONE
                infoButton.setOnClickListener(null)
            }

            editButton.setOnClickListener {
                // Pass the position and the raw text to the fragment
                val fullRawMarkdown = ensureTableSpacing(reasoningText + text)
                onEditAssistantMessage(bindingAdapterPosition, fullRawMarkdown)
            }
            copyButton.setOnClickListener {
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Text", messageTextView.text.toString())
                clipboard.setPrimaryClip(clip)
            }

            copyButton.setOnLongClickListener {
                val fullRawMarkdown = ensureTableSpacing(reasoningText + text)
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Markdown", fullRawMarkdown)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(itemView.context, "Raw Markdown copied to clipboard", Toast.LENGTH_SHORT).show()
                true
            }

            shareButton.setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, messageTextView.text.toString())
                    putExtra(Intent.EXTRA_SUBJECT, "AI Assistant Message")
                }
                itemView.context.startActivity(Intent.createChooser(shareIntent, "Share message via"))
            }

            shareButton.setOnLongClickListener {
                val fullRawMarkdown = ensureTableSpacing(reasoningText + text)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, fullRawMarkdown)
                    putExtra(Intent.EXTRA_SUBJECT, "AI Assistant Raw Markdown")
                }
                itemView.context.startActivity(Intent.createChooser(shareIntent, "Share raw markdown via"))
                Toast.makeText(itemView.context, "Sharing raw markdown", Toast.LENGTH_SHORT).show()
                true
            }

            val iconRes = if (isSpeaking && position == currentPosition) {
                R.drawable.ic_stop_circle
            } else {
                R.drawable.ic_volume_up
            }
            ttsButton.setImageResource(iconRes)

            ttsButton.setOnClickListener {
                val textToSpeak = messageTextView.text.toString()
                if (textToSpeak.isNotEmpty()) {
                    ForegroundService.stopTtsSpeaking()
                    onSpeakText(textToSpeak, position)
                } else {
                    Toast.makeText(itemView.context, "No text to speak", Toast.LENGTH_SHORT).show()
                }
            }

            ttsButton.setOnLongClickListener {
                val textToSpeak = messageTextView.text.toString()
                if (textToSpeak.isNotEmpty()) {
                    ForegroundService.stopTtsSpeaking()
                    onSynthesizeToWavFile(textToSpeak, position)
                } else {
                    Toast.makeText(itemView.context, "No text to save", Toast.LENGTH_SHORT).show()
                }
                true
            }

        }

        private fun exportPdf(message: FlexibleMessage, fullRawMarkdown: String) {
                CoroutineScope(Dispatchers.Main).launch {
                    val pdfUri = withContext(Dispatchers.IO) {
                        try {
                            val generator = PdfGenerator(itemView.context)
                            val imageUriStr = message.imageUri
                            val imageUri = imageUriStr?.toUri()
                            if (imageUri != null) {
                                generator.generateMarkdownPdfWithImage(fullRawMarkdown, imageUri.toString())
                            } else {
                                generator.generateMarkdownPdf(fullRawMarkdown)
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (pdfUri != null) {
                        val context = itemView.context

                        // Disable StrictMode check for file:// URI
                        try {
                            val m = StrictMode::class.java.getMethod("disableDeathOnFileUriExposure")
                            m.invoke(null)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        // Define the target folder path (keeping 'oxproxion')
                        val path = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "oxproxion")

                        // Create intent to view the folder
                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.setDataAndType(Uri.fromFile(path), "resource/folder")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                        // Create the system chooser intent
                        val chooserIntent = Intent.createChooser(intent, "Open Folder")

                        // Show Snackbar with the action
                        Snackbar.make(itemView, "PDF saved to Downloads", Snackbar.LENGTH_LONG)
                            .setAction("Open Folder") {
                                context.startActivity(chooserIntent)
                            }
                            .show()
                    } else {
                        // Keep the failure toast as it provides immediate error feedback
                        Toast.makeText(itemView.context, "Failed to save PDF", Toast.LENGTH_SHORT).show()
                    }
                }
        }

        internal fun stopPulse() {
            pulseAnimator?.cancel()
            bgColorAnimator?.cancel()
            pulseAnimator = null
            bgColorAnimator = null
            messageContainer.alpha = 1f
            messageContainer.clearAnimation()
            messageContainer.background = ContextCompat.getDrawable(itemView.context, R.drawable.bg_ai_message)
        }

        private fun applyCollapseState(isCollapsed: Boolean) {
            messageTextView.maxLines = if (isCollapsed) 4 else Int.MAX_VALUE
            messageTextView.ellipsize = if (isCollapsed) android.text.TextUtils.TruncateAt.END else null
        }
    }
    inner class HiddenViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}