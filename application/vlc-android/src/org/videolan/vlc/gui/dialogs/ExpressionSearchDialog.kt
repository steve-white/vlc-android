package org.videolan.vlc.gui.dialogs

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.textfield.TextInputEditText
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

    companion object {
        fun newInstance(initialQuery: String? = null) = ExpressionSearchDialog().apply {
            arguments = bundleOf(EXPRESSION_SEARCH_QUERY to initialQuery)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.dialog_expression_search, container, false)
        expressionInputText = view.findViewById(R.id.expression_query)
        searchButton = view.findViewById(R.id.expression_search_button)

        val initialQuery = arguments?.getString(EXPRESSION_SEARCH_QUERY).orEmpty()
        if (initialQuery.isNotEmpty()) {
            expressionInputText.setText(initialQuery)
            expressionInputText.setSelection(initialQuery.length)
        }

        searchButton.setOnClickListener { submitExpression() }
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
        setFragmentResult(CONFIRM_EXPRESSION_SEARCH_DIALOG_RESULT, bundleOf(EXPRESSION_SEARCH_QUERY to query.takeIf { it.isNotEmpty() }))
        dismiss()
    }

    override fun getDefaultState(): Int = STATE_EXPANDED

    override fun initialFocusedView(): View = expressionInputText

    override fun needToManageOrientation(): Boolean = true
}