package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.annotation.AttrRes
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.ComponentLargeActionLabelBinding
import com.github.kr328.clash.design.util.*
import com.google.android.material.card.MaterialCardView

class LargeActionCard @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0
) : MaterialCardView(context, attributeSet, defStyleAttr) {
    private val binding = ComponentLargeActionLabelBinding
        .inflate(context.layoutInflater, this, true)

    var text: CharSequence?
        get() = binding.textView.text
        set(value) {
            binding.textView.text = value
        }

    var subtext: CharSequence?
        get() = binding.subtextView.text
        set(value) {
            binding.subtextView.text = value
        }

    var icon: Drawable?
        get() = binding.iconView.background
        set(value) {
            binding.iconView.background = value
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

    fun setTrailingTextSize(sp: Float) {
        binding.trailingText.textSize = sp
    }

    fun setTrailingText2Color(color: Int) {
        binding.trailingText2.setTextColor(color)
    }

    fun setTrailingText2Size(sp: Float) {
        binding.trailingText2.textSize = sp
    }

    fun setTextSize(sp: Float) {
        binding.textView.textSize = sp
    }

    fun setSubtextSize(sp: Float) {
        binding.subtextView.textSize = sp
    }

    init {
        context.resolveClickableAttrs(attributeSet, defStyleAttr) {
            isFocusable = focusable(true)
            isClickable = clickable(true)
            foreground = foreground() ?: context.selectableItemBackground
        }

        context.theme.obtainStyledAttributes(
            attributeSet,
            R.styleable.LargeActionCard,
            defStyleAttr,
            0
        ).apply {
            try {
                icon = getDrawable(R.styleable.LargeActionCard_icon)
                text = getString(R.styleable.LargeActionCard_text)
                subtext = getString(R.styleable.LargeActionCard_subtext)
            } finally {
                recycle()
            }
        }

        minimumHeight = context.getPixels(R.dimen.large_action_card_min_height)
        radius = context.getPixels(R.dimen.large_action_card_radius).toFloat()
        elevation = context.getPixels(R.dimen.large_action_card_elevation).toFloat()
        setCardBackgroundColor(context.resolveThemedColor(com.google.android.material.R.attr.colorSurface))
    }
}