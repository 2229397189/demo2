<template>
  <div class="code-editor" ref="editorContainer" />
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import loader from '@monaco-editor/loader'

const props = defineProps<{
  modelValue: string
  language: string
  readOnly?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const editorContainer = ref<HTMLElement>()
let editor: any = null

const languageMap: Record<string, string> = {
  python: 'python',
  javascript: 'javascript',
  java: 'java',
}

onMounted(async () => {
  if (!editorContainer.value) return

  const monacoEditor = await loader.init()
  editor = monacoEditor.editor.create(editorContainer.value, {
    value: props.modelValue,
    language: languageMap[props.language] || 'plaintext',
    theme: 'vs-dark',
    automaticLayout: true,
    minimap: { enabled: false },
    fontSize: 14,
    lineNumbers: 'on',
    scrollBeyondLastLine: false,
    readOnly: props.readOnly || false,
    tabSize: 4,
    wordWrap: 'on',
    padding: { top: 12, bottom: 12 },
  })

  editor.onDidChangeModelContent(() => {
    emit('update:modelValue', editor.getValue())
  })
})

watch(
  () => props.language,
  (newLang) => {
    if (editor) {
      const monacoEditor = (window as any).monaco
      if (monacoEditor) {
        monacoEditor.editor.setModelLanguage(
          editor.getModel(),
          languageMap[newLang] || 'plaintext'
        )
      }
    }
  }
)

watch(
  () => props.modelValue,
  (newVal) => {
    if (editor && editor.getValue() !== newVal) {
      editor.setValue(newVal)
    }
  }
)

onUnmounted(() => {
  editor?.dispose()
})

function getValue(): string {
  return editor?.getValue() || ''
}

defineExpose({ getValue })
</script>

<style scoped>
.code-editor {
  width: 100%;
  height: 100%;
  min-height: 300px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  overflow: hidden;
}
</style>
