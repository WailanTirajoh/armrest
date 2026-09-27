package io.github.wailantirajoh.armrest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wailantirajoh.armrest.R
import io.github.wailantirajoh.armrest.core.KeyCode
import io.github.wailantirajoh.armrest.core.KeyModifiers
import io.github.wailantirajoh.armrest.core.ProtocolConstants
import io.github.wailantirajoh.armrest.core.TypingBuffer

private const val SHORTCUT_MODIFIERS = KeyModifiers.COMMAND or KeyModifiers.CONTROL or KeyModifiers.OPTION

/**
 * Panel ketik di atas keyboard HP: apa yang diketik langsung dikirim ke Mac (termasuk Backspace dan
 * koreksi otomatis), plus baris tombol khusus dan modifier yang berlaku untuk tombol berikutnya. Label modifier
 * mengikuti komputernya: ⌘ ⌃ ⌥ ⇧ di Mac, Ctrl Win Alt Shift di Windows (bit protokolnya sama).
 */
@Composable
fun KeyboardPanel(
    onText: (String) -> Unit,
    onKey: (key: KeyCode, modifiers: Int, times: Int) -> Unit,
    platform: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val buffer = remember { TypingBuffer() }
    var field by remember { mutableStateOf(TextFieldValue(buffer.text, TextRange(buffer.text.length))) }
    var modifiers by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val softwareKeyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        softwareKeyboard?.show()
    }

    fun onFieldChange(value: TextFieldValue) {
        val before = buffer.typed
        val edit = buffer.update(value.text)
        var rewritten = buffer.text != value.text // karakter tak terlihat dikembalikan

        if (edit.backspaces > 0) onKey(KeyCode.BACKSPACE, 0, edit.backspaces)
        if (edit.insert.isNotEmpty()) {
            val shortcutKey = if (modifiers and SHORTCUT_MODIFIERS != 0) KeyCode.forChar(edit.insert.first()) else null
            if (shortcutKey != null) {
                // ⌘C dan sejenisnya: kirim sebagai tombol, dan jangan biarkan hurufnya tertinggal di field.
                onKey(shortcutKey, modifiers, 1)
                buffer.revertTo(before)
                rewritten = true
            } else {
                onText(edit.insert)
            }
            modifiers = 0
        }
        if (value.composition == null && buffer.typed.length > 200) {
            buffer.reset()
            rewritten = true
        }
        field = TextFieldValue(
            text = buffer.text,
            selection = TextRange(buffer.text.length),
            composition = if (rewritten) null else value.composition,
        )
    }

    fun pressKey(key: KeyCode) {
        onKey(key, modifiers, 1)
        modifiers = 0
    }

    val fieldLabel = stringResource(R.string.type_to_computer)
    val placeholder = stringResource(R.string.type_placeholder)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                Triple("Esc", KeyCode.ESCAPE, R.string.key_escape),
                Triple("Tab", KeyCode.TAB, R.string.key_tab),
                Triple("←", KeyCode.LEFT, R.string.key_left),
                Triple("↑", KeyCode.UP, R.string.key_up),
                Triple("↓", KeyCode.DOWN, R.string.key_down),
                Triple("→", KeyCode.RIGHT, R.string.key_right),
            ).forEach { (label, key, description) ->
                val spoken = stringResource(description)
                OutlinedButton(
                    onClick = { pressKey(key) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.semantics { contentDescription = spoken },
                ) { Text(label, fontSize = 15.sp) }
            }
            val windows = platform == ProtocolConstants.PLATFORM_WINDOWS
            val chips = if (windows) {
                listOf("Ctrl" to KeyModifiers.CONTROL, "Win" to KeyModifiers.COMMAND, "Alt" to KeyModifiers.OPTION, "Shift" to KeyModifiers.SHIFT)
            } else {
                listOf("⌘" to KeyModifiers.COMMAND, "⌃" to KeyModifiers.CONTROL, "⌥" to KeyModifiers.OPTION, "⇧" to KeyModifiers.SHIFT)
            }
            chips.forEach { (label, bit) ->
                FilterChip(
                    selected = modifiers and bit != 0,
                    onClick = { modifiers = modifiers xor bit },
                    label = { Text(label, fontSize = if (windows) 14.sp else 17.sp) },
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .background(colors.surface, RoundedCornerShape(14.dp))
                .border(2.dp, colors.primary, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = field,
                onValueChange = ::onFieldChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    pressKey(KeyCode.RETURN)
                    buffer.reset()
                    field = TextFieldValue(buffer.text, TextRange(buffer.text.length))
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = fieldLabel },
                decorationBox = { inner ->
                    if (buffer.typed.isEmpty()) {
                        Text(
                            placeholder,
                            color = colors.onSurfaceVariant,
                            fontSize = 16.sp,
                        )
                    }
                    inner()
                },
            )
        }
    }
}
