import { describe, it, expect } from 'vitest'
import { buildDocTree, flattenPositions } from './doc-tree'
import type { Doc } from '@/api/generated'

function makeDoc(name: string, parent?: string, priority?: number): Doc {
  return {
    apiVersion: 'docs.halo.run/v1alpha1',
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
