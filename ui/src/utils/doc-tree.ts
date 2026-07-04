import type { Doc } from '@/api/generated'

/**
 * 树节点：包裹一篇文档及其子节点。`children` 键名与 he-tree 的 childrenKey 对齐。
 */
export interface DocTreeNode {
  doc: Doc
  children: DocTreeNode[]
}

/**
 * 把平铺的文档列表按 spec.parent 组织成树，同级按 spec.priority 升序（相同则按标题）。
 *
 * 顶层节点 = parent 为空，或 parent 指向的文档不在列表中（悬挂节点上浮为顶层，避免丢失）。
 * 对存在的父子环不做特殊处理（数据层不应出现），仅保证每个节点只挂一次。
 */
export function buildDocTree(docs: Doc[]): DocTreeNode[] {
  const nodeMap = new Map<string, DocTreeNode>()
  for (const doc of docs) {
    nodeMap.set(doc.metadata.name, { doc, children: [] })
  }

  const roots: DocTreeNode[] = []
  for (const node of nodeMap.values()) {
    const parentName = node.doc.spec.parent
    const parent = parentName ? nodeMap.get(parentName) : undefined
    if (parent && parent !== node) {
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  }

  sortNodes(roots)
  return roots
}

function sortNodes(nodes: DocTreeNode[]) {
  nodes.sort(compareNode)
  for (const node of nodes) {
    if (node.children.length) {
      sortNodes(node.children)
    }
  }
}

function compareNode(a: DocTreeNode, b: DocTreeNode): number {
  const pa = a.doc.spec.priority ?? 0
  const pb = b.doc.spec.priority ?? 0
  if (pa !== pb) {
    return pa - pb
  }
  return a.doc.spec.title.localeCompare(b.doc.spec.title)
}

/**
 * 一个节点的期望位置：新的 parent（顶层为空字符串）与在同级中的序号。
 */
export interface DocPosition {
  name: string
  parent: string
  priority: number
}

/**
 * 遍历树，得出每个节点「应有」的 parent 与 priority（= 在同级中的索引）。
 * 用于拖拽后与当前值对比，只对发生变化的节点落库。
 */
export function flattenPositions(tree: DocTreeNode[]): DocPosition[] {
  const result: DocPosition[] = []
  const walk = (nodes: DocTreeNode[], parent: string) => {
    nodes.forEach((node, index) => {
      result.push({ name: node.doc.metadata.name, parent, priority: index })
      if (node.children.length) {
        walk(node.children, node.doc.metadata.name)
      }
    })
  }
  walk(tree, '')
  return result
}
