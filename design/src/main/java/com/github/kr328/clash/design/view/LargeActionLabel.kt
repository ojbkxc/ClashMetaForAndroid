package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.ComponentLargeActionLabelBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveClickableAttrs
import com.github.kr328.clash.design.util.selectableItemBackground

class LargeActionLabel @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : FrameLayout(context, attributeSet, defStyleAttr, defStyleRes) {
    private val binding = ComponentLargeActionLabelBinding
        .inflate(context.layoutInflater, this, true)

    var icon: Drawable?
        get() = binding.iconView.background
        set(value) {
            binding.iconView.background = value
        }

    var text: CharSequence?
        get() = binding.textView.text
        set(value) {
            binding.textView.text = value
        }

    var subtext: CharSequence?
        get() = binding.subtextView.text
        set(value) {
            binding.subtextView.text = value

            if (value == null) {
                binding.subtextView.visibility = View.GONE
            } else {
                binding.subtextView.visibility = View.VISIBLE
            }
        }

    var trailingText: CharSequence?
        get() = binding.trailingText.text
        set(value) {
            binding.trailingText.text = value
            binding.trailingText.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
            updateTrailingContainer()
        }

    var trailingText2: CharSequence?
        get() = binding.trailingText2.text
        set(value) {
            binding.trailingText2.text = value
            binding.trailingText2.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
            updateTrailingContainer()
        }

    private fun updateTrailingContainer() {
        binding.trailingContainer.visibility = if (
            binding.trailingText.visibility == View.VISIBLE ||
            binding.trailingText2.visibility == View.VISIBLE
        ) View.VISIBLE else View.GONE
    }

    fun setTrailingTextColor(color: Int) {
        binding.trailingText.setTextColor(color)
    }

    fun setTrailingText2Color(color: Int) {
        binding.trailingText2.setTextColor(color)
    }

    init {
        context.resolveClickableAttrs(
            attributeSet,
            defStyleAttr,
            defStyleRes
        ) {
            isFocusable = focusable(true)
            isClickable = clickable(true)
            background = background() ?: context.selectableItemBackground
        }

        context.theme.obtainStyledAttributes(
            attributeSet,
            R.styleable.LargeActionLabel,
            defStyleAttr,
            defStyleRes
        ).apply {
            try {
                icon = getDrawable(R.styleable.LargeActionLabel_icon)
                text = getString(R.styleable.LargeActionLabel_text)
                subtext = getString(R.styleable.LargeActionLabel_subtext)
            } finally {
                recycle()
            }
        }
    }
}