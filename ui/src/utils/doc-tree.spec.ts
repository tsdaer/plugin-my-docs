import { describe, it, expect } from 'vitest'
import { buildDocTree, flattenPositions, moveNode, isSelfOrDescendant } from './doc-tree'
import type { Doc } from '@/api/generated'

function makeDoc(name: string, parent?: string, priority?: number): Doc {
  return {
    apiVersion: 'my-docs.tsdaer.run/v1alpha1',
    kind: 'Doc',
    metadata: { name },
    spec: {
      title: name,
      slug: name,
      libraryName: 'lib',
      parent,
      priority,
    },
  }
}

describe('buildDocTree', () => {
  it('组装单层节点并按 priority 升序', () => {
    const tree = buildDocTree([makeDoc('b', undefined, 2), makeDoc('a', undefined, 1)])
    expect(tree.map((n) => n.doc.metadata.name)).toEqual(['a', 'b'])
  })

  it('按 parent 嵌套子节点', () => {
    const tree = buildDocTree([
      makeDoc('root', undefined, 0),
      makeDoc('child2', 'root', 1),
      makeDoc('child1', 'root', 0),
    ])
    expect(tree).toHaveLength(1)
    expect(tree[0].children.map((n) => n.doc.metadata.name)).toEqual(['child1', 'child2'])
  })

  it('父节点不存在时上浮为顶层', () => {
    const tree = buildDocTree([makeDoc('orphan', 'missing', 0)])
    expect(tree).toHaveLength(1)
    expect(tree[0].doc.metadata.name).toBe('orphan')
  })
})

describe('flattenPositions', () => {
  it('得出每个节点的 parent 与同级序号', () => {
    const tree = buildDocTree([
      makeDoc('root', undefined, 0),
      makeDoc('c1', 'root', 0),
      makeDoc('c2', 'root', 1),
    ])
    const positions = flattenPositions(tree)
    expect(positions).toEqual([
      { name: 'root', parent: '', priority: 0 },
      { name: 'c1', parent: 'root', priority: 0 },
      { name: 'c2', parent: 'root', priority: 1 },
    ])
  })
})

describe('isSelfOrDescendant', () => {
  it('自身与后代均返回 true，其它 false', () => {
    const tree = buildDocTree([
      makeDoc('root', undefined, 0),
      makeDoc('child', 'root', 0),
      makeDoc('grandchild', 'child', 0),
      makeDoc('other', undefined, 1),
    ])
    const root = tree[0]
    expect(isSelfOrDescendant(root, 'root')).toBe(true)
    expect(isSelfOrDescendant(root, 'grandchild')).toBe(true)
    expect(isSelfOrDescendant(root, 'other')).toBe(false)
  })
})

describe('moveNode', () => {
  const base = () =>
    buildDocTree([
      makeDoc('a', undefined, 0),
      makeDoc('b', undefined, 1),
      makeDoc('c', undefined, 2),
    ])

  it('inside：成为目标的子节点', () => {
    const next = moveNode(base(), 'a', 'b', 'inside')
    const positions = flattenPositions(next)
    expect(positions.find((p) => p.name === 'a')).toEqual({
      name: 'a',
      parent: 'b',
      priority: 0,
    })
    // b、c 仍为顶层
    expect(positions.filter((p) => p.parent === '').map((p) => p.name)).toEqual(['b', 'c'])
  })

  it('before：插到目标之前成为兄弟', () => {
    const next = moveNode(base(), 'c', 'a', 'before')
    const roots = flattenPositions(next)
      .filter((p) => p.parent === '')
      .sort((x, y) => x.priority - y.priority)
      .map((p) => p.name)
    expect(roots).toEqual(['c', 'a', 'b'])
  })

  it('after：插到目标之后成为兄弟', () => {
    const next = moveNode(base(), 'a', 'b', 'after')
    const roots = flattenPositions(next)
      .filter((p) => p.parent === '')
      .sort((x, y) => x.priority - y.priority)
      .map((p) => p.name)
    expect(roots).toEqual(['b', 'a', 'c'])
  })

  it('禁止拖到自身的子树，原样返回', () => {
    const tree = buildDocTree([makeDoc('root', undefined, 0), makeDoc('child', 'root', 0)])
    const next = moveNode(tree, 'root', 'child', 'inside')
    expect(next).toBe(tree)
  })
})

