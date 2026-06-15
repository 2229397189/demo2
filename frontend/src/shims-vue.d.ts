declare module 'element-plus/dist/locale/zh-cn.mjs' {
  const zhCn: Record<string, any>
  export default zhCn
}

declare module '@monaco-editor/loader' {
  const loader: {
    init: () => Promise<any>
  }
  export default loader
}
