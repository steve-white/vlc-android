package org.videolan.vlc.gui.dialogs

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.UiTools

const val EXPRESSION_SEARCH_QUERY = "EXPRESSION_SEARCH_QUERY"
const val CONFIRM_EXPRESSION_SEARCH_DIALOG_RESULT = "CONFIRM_EXPRESSION_SEARCH_DIALOG_RESULT"

class ExpressionSearchDialog : VLCBottomSheetDialogFragment() {

    private lateinit var searchButton: Button
    private lateinit var expressionInputText: TextInputEditText
    private lateinit var wildcardCheckBox: CheckBox

    private val dictationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (spoken.isNullOrEmpty()) return@registerForActivityResult
        expressionInputText.setText(spoken)
        expressionInputText.setSelection(spoken.length)
    }

    companion object {
        fun newInstance(initialQuery: String? = null) = ExpressionSearchDialog().apply {
            arguments = bundleOf(EXPRESSION_SEARCH_QUERY to initialQuery)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.dialog_expression_search, container, false)
        expressionInputText = view.findViewById(R.id.expression_query)
        searchButton = view.findViewById(R.id.expression_search_button)
        wildcardCheckBox = view.findViewById(R.id.expression_wildcard_checkbox)

        var initialQuery = arguments?.getString(EXPRESSION_SEARCH_QUERY).orEmpty()
        // The implicit wildcards are hidden from the input: strip them and tick the box, otherwise show the raw query
        if (initialQuery.length > 2 && initialQuery.startsWith('*') && initialQuery.endsWith('*')) {
            initialQuery = initialQuery.substring(1, initialQuery.length - 1)
            wildcardCheckBox.isChecked = true
        } else if (initialQuery.isNotEmpty()) {
            wildcardCheckBox.isChecked = false
        }
        if (initialQuery.isNotEmpty()) {
            expressionInputText.setText(initialQuery)
            expressionInputText.setSelection(initialQuery.length)
        }

        searchButton.setOnClickListener { submitExpression() }
        val inputLayout = view.findViewById<TextInputLayout>(R.id.expression_input_layout)
        val dictationIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_expression_dictate))
        }
        if (dictationIntent.resolveActivity(requireContext().packageManager) == null) {
            inputLayout.isEndIconVisible = false
        } else {
            inputLayout.setEndIconOnClickListener {
                try {
                    dictationLauncher.launch(dictationIntent)
                } catch (e: ActivityNotFoundException) {
                    inputLayout.isEndIconVisible = false
                }
            }
        }
        expressionInputText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitExpression()
                true
            } else false
        }
        expressionInputText.setOnKeyListener { _, keyCode, keyEvent ->
            if (keyCode == EditorInfo.IME_ACTION_DONE ||
                keyCode == EditorInfo.IME_ACTION_GO ||
                keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
                submitExpression()
                true
            } else false
        }
        lifecycleScope.launch(Dispatchers.IO) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(100)
                withContext(Dispatchers.Main) {
                    expressionInputText.requestFocus()
                    UiTools.setKeyboardVisibility(expressionInputText, true)
                }
            }
        }
        return view
    }

    private fun submitExpression() {
        val query = expressionInputText.text?.toString()?.trim().orEmpty()
        val expression = if (query.isNotEmpty() && wildcardCheckBox.isChecked) "${if (query.startsWith('*')) "" else "*"}$query${if (query.endsWith('*')) "" else "*"}" else query
        setFragmentResult(CONFIRM_EXPRESSION_SEARCH_DIALOG_RESULT, bundleOf(EXPRESSION_SEARCH_QUERY to expression.takeIf { it.isNotEmpty() }))
        dismiss()
    }

    override fun getDefaultState(): Int = STATE_EXPANDED

    override fun initialFocusedView(): View = expressionInputText

    override fun needToManageOrientation(): Boolean = true
}