import type { Doc } from '@/api/generated'

/**
 * 树节点：包裹一篇文档及其子节点。
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

/** 在树中按 name 查找节点。 */
export function findNode(tree: DocTreeNode[], name: string): DocTreeNode | undefined {
  for (const node of tree) {
    if (node.doc.metadata.name === name) {
      return node
    }
    const found = findNode(node.children, name)
    if (found) {
      return found
    }
  }
  return undefined
}

/** target 是否为 source 自身或其后代（用于禁止把节点拖进自己的子树）。 */
export function isSelfOrDescendant(source: DocTreeNode, targetName: string): boolean {
  if (source.doc.metadata.name === targetName) {
    return true
  }
  return source.children.some((child) => isSelfOrDescendant(child, targetName))
}

export type DropZone = 'before' | 'inside' | 'after'

/**
 * 将 sourceName 节点移动到 targetName 的相对位置，返回新的树（不可变，原树不动）。
 * - inside：成为 target 的子节点（追加到末尾）
 * - before/after：成为 target 的兄弟，插到其前/后
 *
 * 非法移动（拖到自身或自己的子树、找不到节点）时原样返回，由调用方保证已拦截。
 */
export function moveNode(
  tree: DocTreeNode[],
  sourceName: string,
  targetName: string,
  zone: DropZone,
): DocTreeNode[] {
  if (sourceName === targetName) {
    return tree
  }
  // 深拷贝一层结构（保留 doc 引用），避免直接改动响应式源。
  const clone = (nodes: DocTreeNode[]): DocTreeNode[] =>
    nodes.map((n) => ({ doc: n.doc, children: clone(n.children) }))
  const next = clone(tree)

  const source = findNode(next, sourceName)
  const target = findNode(next, targetName)
  if (!source || !target || isSelfOrDescendant(source, targetName)) {
    return tree
  }

  // 从原位置摘除 source。
  const detach = (nodes: DocTreeNode[]): boolean => {
    const idx = nodes.findIndex((n) => n.doc.metadata.name === sourceName)
    if (idx >= 0) {
      nodes.splice(idx, 1)
      return true
    }
    return nodes.some((n) => detach(n.children))
  }
  detach(next)

  // 找到 target 所在的父数组与索引，用于 before/after 插入。
  const locate = (
    nodes: DocTreeNode[],
  ): { siblings: DocTreeNode[]; index: number } | undefined => {
    const idx = nodes.findIndex((n) => n.doc.metadata.name === targetName)
    if (idx >= 0) {
      return { siblings: nodes, index: idx }
    }
    for (const n of nodes) {
      const found = locate(n.children)
      if (found) {
        return found
      }
    }
    return undefined
  }

  if (zone === 'inside') {
    target.children.push(source)
  } else {
    const loc = locate(next)
    if (!loc) {
      return tree
    }
    const insertAt = zone === 'before' ? loc.index : loc.index + 1
    loc.siblings.splice(insertAt, 0, source)
  }

  return next
}
