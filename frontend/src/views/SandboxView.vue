<template>
  <div class="sandbox-view">
    <div class="page-header">
      <div>
        <h2>代码沙箱</h2>
        <p>安全执行代码片段，支持 Python、JavaScript、Java</p>
      </div>
      <div class="header-actions">
        <el-select v-model="language" style="width: 140px">
          <el-option label="Python" value="python" />
          <el-option label="JavaScript" value="javascript" />
          <el-option label="Java" value="java" />
        </el-select>
        <el-button type="primary" @click="handleRun" :loading="running" :disabled="!code.trim()">
          <el-icon><VideoPlay /></el-icon>
          运行
        </el-button>
        <el-button @click="handleClear">
          <el-icon><Delete /></el-icon>
          清空
        </el-button>
      </div>
    </div>

    <div class="sandbox-content">
      <div class="editor-panel">
        <div class="panel-header">
          <span>代码编辑器</span>
          <el-dropdown @command="handleTemplate">
            <el-button size="small" text>
              加载模板
              <el-icon><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="hello">Hello World</el-dropdown-item>
                <el-dropdown-item command="fibonacci">斐波那契数列</el-dropdown-item>
                <el-dropdown-item command="sort">排序算法</el-dropdown-item>
                <el-dropdown-item command="api">API调用示例</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <CodeEditor
          v-model="code"
          :language="language"
          ref="editorRef"
        />
      </div>

      <div class="output-panel">
        <OutputConsole
          :output="output"
          :error="error"
          :execution-time="executionTime"
          @clear="handleClearOutput"
        />
      </div>
    </div>

    <el-card class="info-card" shadow="hover">
      <template #header>
        <span>沙箱信息</span>
      </template>
      <div class="info-grid">
        <div class="info-item">
          <label>语言</label>
          <span>{{ language }}</span>
        </div>
        <div class="info-item">
          <label>超时时间</label>
          <span>30秒</span>
        </div>
        <div class="info-item">
          <label>内存限制</label>
          <span>256MB</span>
        </div>
        <div class="info-item">
          <label>执行时间</label>
          <span>{{ executionTime > 0 ? executionTime + 'ms' : '-' }}</span>
        </div>
        <div class="info-item">
          <label>内存使用</label>
          <span>{{ memoryUsage > 0 ? formatMemory(memoryUsage) : '-' }}</span>
        </div>
        <div class="info-item">
          <label>退出码</label>
          <span :class="{ 'exit-error': exitCode !== 0 && exitCode !== -1 }">
            {{ exitCode >= 0 ? exitCode : '-' }}
          </span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { VideoPlay, Delete, ArrowDown } from '@element-plus/icons-vue'
import * as sandboxApi from '@/api/sandbox'
import CodeEditor from '@/components/sandbox/CodeEditor.vue'
import OutputConsole from '@/components/sandbox/OutputConsole.vue'

const language = ref('python')
const code = ref('')
const output = ref('')
const error = ref('')
const executionTime = ref(0)
const memoryUsage = ref(0)
const exitCode = ref(-1)
const running = ref(false)
const editorRef = ref<InstanceType<typeof CodeEditor>>()

const templates: Record<string, Record<string, string>> = {
  python: {
    hello: 'print("Hello, World!")',
    fibonacci: `def fibonacci(n):
    if n <= 1:
        return n
    return fibonacci(n - 1) + fibonacci(n - 2)

for i in range(10):
    print(f"fibonacci({i}) = {fibonacci(i)}")`,
    sort: `import random

def quicksort(arr):
    if len(arr) <= 1:
        return arr
    pivot = arr[len(arr) // 2]
    left = [x for x in arr if x < pivot]
    middle = [x for x in arr if x == pivot]
    right = [x for x in arr if x > pivot]
    return quicksort(left) + middle + quicksort(right)

data = [random.randint(1, 100) for _ in range(20)]
print(f"Original: {data}")
print(f"Sorted: {quicksort(data)}")`,
    api: `import json

# 模拟API响应
response = {
    "status": "success",
    "data": {
        "users": [
            {"id": 1, "name": "Alice", "age": 30},
            {"id": 2, "name": "Bob", "age": 25},
        ]
    }
}

print(json.dumps(response, indent=2))`,
  },
  javascript: {
    hello: 'console.log("Hello, World!");',
    fibonacci: `function fibonacci(n) {
    if (n <= 1) return n;
    return fibonacci(n - 1) + fibonacci(n - 2);
}

for (let i = 0; i < 10; i++) {
    console.log(\`fibonacci(\${i}) = \${fibonacci(i)}\`);
}`,
    sort: `function quicksort(arr) {
    if (arr.length <= 1) return arr;
    const pivot = arr[Math.floor(arr.length / 2)];
    const left = arr.filter(x => x < pivot);
    const middle = arr.filter(x => x === pivot);
    const right = arr.filter(x => x > pivot);
    return [...quicksort(left), ...middle, ...quicksort(right)];
}

const data = Array.from({length: 20}, () => Math.floor(Math.random() * 100));
console.log("Original:", data);
console.log("Sorted:", quicksort(data));`,
    api: `const response = {
    status: "success",
    data: {
        users: [
            {id: 1, name: "Alice", age: 30},
            {id: 2, name: "Bob", age: 25},
        ]
    }
};

console.log(JSON.stringify(response, null, 2));`,
  },
  java: {
    hello: `public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}`,
    fibonacci: `public class Main {
    static int fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }

    public static void main(String[] args) {
        for (int i = 0; i < 10; i++) {
            System.out.printf("fibonacci(%d) = %d%n", i, fibonacci(i));
        }
    }
}`,
    sort: `import java.util.*;

public class Main {
    static List<Integer> quicksort(List<Integer> arr) {
        if (arr.size() <= 1) return arr;
        int pivot = arr.get(arr.size() / 2);
        List<Integer> left = new ArrayList<>();
        List<Integer> middle = new ArrayList<>();
        List<Integer> right = new ArrayList<>();
        for (int x : arr) {
            if (x < pivot) left.add(x);
            else if (x == pivot) middle.add(x);
            else right.add(x);
        }
        List<Integer> result = new ArrayList<>();
        result.addAll(quicksort(left));
        result.addAll(middle);
        result.addAll(quicksort(right));
        return result;
    }

    public static void main(String[] args) {
        Random rand = new Random();
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 20; i++) data.add(rand.nextInt(100));
        System.out.println("Original: " + data);
        System.out.println("Sorted: " + quicksort(data));
    }
}`,
    api: `public class Main {
    public static void main(String[] args) {
        String json = """
            {
                "status": "success",
                "data": {
                    "users": [
                        {"id": 1, "name": "Alice", "age": 30},
                        {"id": 2, "name": "Bob", "age": 25}
                    ]
                }
            }
            """;
        System.out.println(json);
    }
}`,
  },
}

async function handleRun() {
  if (!code.value.trim()) {
    ElMessage.warning('请输入代码')
    return
  }
  running.value = true
  output.value = ''
  error.value = ''
  executionTime.value = 0
  memoryUsage.value = 0
  exitCode.value = -1

  try {
    const res = await sandboxApi.executeCode({
      language: language.value as any,
      code: code.value,
      timeout: 30,
    })
    output.value = res.data.output
    error.value = res.data.error
    executionTime.value = res.data.executionTime
    memoryUsage.value = res.data.memoryUsage
    exitCode.value = res.data.exitCode

    if (res.data.exitCode === 0) {
      ElMessage.success('执行完成')
    } else {
      ElMessage.warning('执行完成，但有错误')
    }
  } catch (err) {
    ElMessage.error('执行失败')
    console.error('Execution failed:', err)
  } finally {
    running.value = false
  }
}

function handleClear() {
  code.value = ''
  handleClearOutput()
}

function handleClearOutput() {
  output.value = ''
  error.value = ''
  executionTime.value = 0
  memoryUsage.value = 0
  exitCode.value = -1
}

function handleTemplate(template: string) {
  const langTemplates = templates[language.value]
  if (langTemplates && langTemplates[template]) {
    code.value = langTemplates[template]
  }
}

function formatMemory(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}
</script>

<style scoped>
.sandbox-view {
  padding: 24px;
  height: 100%;
  overflow-y: auto;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 24px;
}

.page-header h2 {
  margin: 0 0 8px;
  font-size: 24px;
  color: #303133;
}

.page-header p {
  margin: 0;
  color: #909399;
  font-size: 14px;
}

.header-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.sandbox-content {
  display: flex;
  gap: 16px;
  margin-bottom: 24px;
  height: 500px;
}

.editor-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: #f5f7fa;
  border-bottom: 1px solid #e4e7ed;
  font-size: 14px;
  font-weight: 500;
}

.output-panel {
  width: 400px;
  flex-shrink: 0;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.info-item label {
  font-size: 12px;
  color: #909399;
}

.info-item span {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
}

.exit-error {
  color: #f56c6c;
}
</style>
