package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The whole row toggles the box. One TalkBack target that says «отмечено / не отмечено» (a clickable row next to
 * its own checkbox used to be two targets, and the row itself announced no state); at least 48 dp tall.
 */
@Composable
fun CheckRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
             content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) }
        content()
    }
}

/** Same for a single choice: the row is the radio button. */
@Composable
fun RadioRow(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { RadioButton(selected = selected, onClick = null) }
        content()
    }
}

/**
 * A settings line whose title, hint and switch are one control: tapping the text toggles it too, and TalkBack reads
 * «название, переключатель, включено» instead of an unlabeled switch next to separate text.
 */
@Composable
fun SwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
              verticalAlignment: Alignment.Vertical = Alignment.CenterVertically, switchModifier: Modifier = Modifier,
              content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = verticalAlignment, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = switchModifier)
    }
}

/** Switch first, then its label (device screen style). */
@Composable
fun LeadingSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = checked, onCheckedChange = null)
        content()
    }
}
