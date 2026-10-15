package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 手机按列表与编辑区顺序进入，宽屏并排展示；当前场景以文字和色调共同标识。 */
@Composable
internal fun ScenesScreen(state: ScenesUiState, actions: SimulSubActions, wide: Boolean) {
    val editor = state.editor
    val pageStateHolder = rememberSaveableStateHolder()
    // 手机列表与编辑器结构不同，必须分开保存；平板编辑时左侧列表继续使用原状态。
    val pageKey = if (wide) "scene-columns" else if (editor == null) "scene-list" else "scene-editor"
    pageStateHolder.SaveableStateProvider(pageKey) {
        PageScrollStateProvider {
            ScenePageColumns(state, actions, wide)
        }
    }
}

/** 列表与表单只读取控制器草稿，滚动保存范围由外层页面容器管理。 */
@Composable
private fun ScenePageColumns(state: ScenesUiState, actions: SimulSubActions, wide: Boolean) {
    val editor = state.editor
    val existing = state.items.any { it.id == editor?.id }
    PageColumns(wide, first = {
        if (wide || editor == null) {
            item("scenes-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("内容场景", "保存内容背景与固定译法，术语在目标语言匹配时使用。")
                    Button(onClick = actions.onCreateScene, enabled = editor?.let { !it.generating && !it.saving } ?: true, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.heightIn(min = 48.dp)) { Text("新建场景") }
                    state.message?.let { MessagePanel(it) }
                }
            }
            if (state.items.isEmpty()) item("scenes-empty") { SupportingText("暂无场景。新建一个场景，写下内容背景与常用术语。") }
            items(state.items, key = { "scene:${it.id}" }) { scene ->
                val selected = scene.id == state.selectedId
                Card(
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(scene.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(if (selected) "当前使用 · ${if (scene.builtin) "内置场景" else "自建场景"}" else if (scene.builtin) "内置场景" else "自建场景", color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        if (scene.background.isNotEmpty()) SupportingText(scene.background)
                        SupportingText("${scene.languageLabel} · ${scene.termCount} 条术语")
                        if (scene.summary.isNotEmpty()) SupportingText(scene.summary)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ActionRow {
                            if (!selected) FilledTonalButton(onClick = { actions.onSelectScene(scene.id) }, enabled = editor?.let { !it.generating && !it.saving } ?: true, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.heightIn(min = 48.dp)) { Text("使用此场景") }
                            FilledTonalButton(onClick = { actions.onEditScene(scene.id) }, enabled = editor?.let { !it.generating && !it.saving } ?: true, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.heightIn(min = 48.dp)) { Text("编辑场景") }
                        }
                    }
                }
            }
        }
        if (!wide && editor != null) sceneEditorItems(editor, actions, existing)
    }, second = {
        if (wide) {
            if (editor != null) sceneEditorItems(editor, actions, existing)
            else item("scene-editor-empty") {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeading("场景编辑区", "选择左侧场景的「编辑场景」，或新建场景。")
                        SupportingText("背景与术语会显示在这里，保存后用于同传。")
                    }
                }
            }
        }
    })
}

/** 编辑器按稳定标识构造惰性表单，增删术语时不重建其余输入框。 */
private fun LazyListScope.sceneEditorItems(editor: SceneEditorUiState, actions: SimulSubActions, existing: Boolean) {
    val enabled = !editor.saving && !editor.generating
    item("editor:${editor.id}:heading") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(if (existing) "编辑场景" else "新建场景")
            TextField(
                        shape = TextFieldDefaults.shape,
                        colors = TextFieldDefaults.colors(),value = editor.name, onValueChange = { actions.onSceneDraftChange(editor.copy(name = it)) }, label = { Text("场景名称") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
            TextField(
                        shape = TextFieldDefaults.shape,
                        colors = TextFieldDefaults.colors(),value = editor.background, onValueChange = { actions.onSceneDraftChange(editor.copy(background = it)) }, label = { Text("内容背景") }, supportingText = { Text("写下题材、角色、专有名词或希望保持的语气。") }, enabled = enabled, minLines = 3, modifier = Modifier.fillMaxWidth())
            OptionPicker("术语译文语言", editor.termsLanguageId, editor.languageOptions, enabled) { actions.onSceneDraftChange(editor.copy(termsLanguageId = it)) }
            editor.message?.let { MessagePanel(it) }
            SectionHeading("固定译法", "原文写视频里出现的词，译文写希望采用的说法。")
        }
    }
    itemsIndexed(editor.terms, key = { _, term -> "term:${term.id}" }) { index, term ->
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("术语 ${index + 1}", style = MaterialTheme.typography.labelLarge)
                TextField(
                        shape = TextFieldDefaults.shape,
                        colors = TextFieldDefaults.colors(),
                    value = term.original,
                    onValueChange = { value -> actions.onSceneDraftChange(editor.copy(terms = editor.terms.map { if (it.id == term.id) it.copy(original = value) else it })) },
                    label = { Text("术语原文") }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                        shape = TextFieldDefaults.shape,
                        colors = TextFieldDefaults.colors(),
                    value = term.translated,
                    onValueChange = { value -> actions.onSceneDraftChange(editor.copy(terms = editor.terms.map { if (it.id == term.id) it.copy(translated = value) else it })) },
                    label = { Text("指定译法") }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { actions.onRemoveTerm(term.id) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除此术语") }
            }
        }
    }
    item("editor:${editor.id}:generation") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editor.terms.isEmpty()) SupportingText("还没有术语，可以手动添加或使用已保存的翻译服务生成。")
            FilledTonalButton(onClick = actions.onAddTerm, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("添加术语") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionHeading("生成常用术语", "使用已保存的翻译服务生成，只追加新术语。")
            TextField(
                        shape = TextFieldDefaults.shape,
                        colors = TextFieldDefaults.colors(),value = editor.generationCount, onValueChange = { actions.onSceneDraftChange(editor.copy(generationCount = it)) }, label = { Text("最多生成条数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = enabled, modifier = Modifier.fillMaxWidth())
            FilledTonalButton(onClick = actions.onGenerateTerms, enabled = enabled && editor.canGenerate, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (editor.generating) "正在生成…" else "生成术语") }
            if (!editor.canGenerate && !editor.generating) SupportingText("请先保存翻译服务配置，并填写场景名称或背景。")
            if (editor.generating || editor.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ActionRow {
                Button(onClick = actions.onSaveScene, enabled = enabled, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (editor.saving) "正在保存…" else "保存场景") }
                FilledTonalButton(onClick = actions.onCloseSceneEditor, enabled = enabled, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭编辑") }
            }
            if (existing) SceneDestructiveActions(editor, enabled, actions)
        }
    }
}

/** 内置场景恢复和自建场景删除均先确认具体影响，避免误触。 */
@Composable
private fun SceneDestructiveActions(editor: SceneEditorUiState, enabled: Boolean, actions: SimulSubActions) {
    var confirming by rememberSaveable(editor.id) { mutableStateOf(false) }
    val id = editor.id ?: return
    val label = if (editor.builtin) "恢复默认" else "删除场景"
    TextButton(onClick = { confirming = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(label, color = MaterialTheme.colorScheme.error) }
    if (confirming) ConfirmSceneAction(
        title = "$label「${editor.name}」？",
        detail = if (editor.builtin) "将恢复此场景自带的名称、背景和术语，你对该场景的修改将丢失。" else "将删除场景「${editor.name}」及其中全部术语。如果正在使用此场景，将自动切换到其他可用场景。",
        confirmLabel = label, onDismiss = { confirming = false },
        onConfirm = { confirming = false; if (editor.builtin) actions.onResetScene(id) else actions.onDeleteScene(id) },
    )
}
