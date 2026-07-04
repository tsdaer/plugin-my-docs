<script setup lang="ts">
import { VStatusDot, VDropdown, VDropdownItem, IconMore } from '@halo-dev/components'
import RiArrowRightSLine from '~icons/ri/arrow-right-s-line'
import RiDraggable from '~icons/ri/draggable'
import type { DocTreeNode } from '@/utils/doc-tree'

defineProps<{
  nodes: DocTreeNode[]
  // 当前正被拖拽的节点 name（用于禁止拖到自身/子树，样式高亮由父层统一算）
  draggingName: string
  // 当前 dragover 命中的节点 name 与落点区域
  overName: string
  overZone: '' | 'before' | 'inside' | 'after'
  expanded: Set<string>
}>()

const emit = defineEmits<{
  (e: 'toggle', name: string): void
  (e: 'open', node: DocTreeNode): void
  (e: 'create-child', parent: string): void
  (e: 'edit', node: DocTreeNode): void
  (e: 'delete', node: DocTreeNode): void
  (e: 'node-dragstart', name: string): void
  (e: 'node-dragend'): void
  (e: 'node-dragover', payload: { name: string; zone: 'before' | 'inside' | 'after' }): void
  (e: 'node-drop'): void
}>()

// 依据鼠标在节点行内的纵向位置判定落点：上 25% = before，下 25% = after，中间 = inside（成为子节点）。
function onDragOver(event: DragEvent, name: string) {
  const el = event.currentTarget as HTMLElement
  const rect = el.getBoundingClientRect()
  const offset = event.clientY - rect.top
  const ratio = offset / rect.height
  let zone: 'before' | 'inside' | 'after' = 'inside'
  if (ratio < 0.25) {
    zone = 'before'
  } else if (ratio > 0.75) {
    zone = 'after'
  }
  emit('node-dragover', { name, zone })
}
</script>

<template>
  <ul class="doc-tree-list">
    <li v-for="node in nodes" :key="node.doc.metadata.name" class="doc-tree-item">
      <div
        class="doc-tree-row"
        :class="{
          'is-dragging': draggingName === node.doc.metadata.name,
          'over-before': overName === node.doc.metadata.name && overZone === 'before',
          'over-inside': overName === node.doc.metadata.name && overZone === 'inside',
          'over-after': overName === node.doc.metadata.name && overZone === 'after',
        }"
        @dragover.prevent.stop="onDragOver($event, node.doc.metadata.name)"
        @drop.prevent.stop="emit('node-drop')"
      >
        <span
          class="doc-tree-handle"
          v-tooltip="'拖拽调整层级/排序'"
          draggable="true"
          @dragstart.stop="emit('node-dragstart', node.doc.metadata.name)"
          @dragend.stop="emit('node-dragend')"
        >
          <RiDraggable />
        </span>

        <button
          class="doc-tree-toggle"
          :class="{ 'is-open': expanded.has(node.doc.metadata.name) }"
          :style="{ visibility: node.children.length ? 'visible' : 'hidden' }"
          type="button"
          @click.stop="emit('toggle', node.doc.metadata.name)"
        >
          <RiArrowRightSLine />
        </button>

        <div class="doc-tree-main">
          <span
            class="doc-tree-title"
            v-tooltip="'点击编辑'"
            @click.stop="emit('open', node)"
          >
            {{ node.doc.spec.title }}
          </span>
          <span class="doc-tree-slug">{{ node.doc.spec.slug }}</span>
          <VStatusDot
            v-tooltip="node.doc.spec.published ? '已发布' : '草稿'"
            :state="node.doc.spec.published ? 'success' : 'default'"
          />
        </div>

        <VDropdown>
          <IconMore class="doc-tree-more" />
          <template #popper>
            <VDropdownItem @click="emit('create-child', node.doc.metadata.name)">
              新建子文档
            </VDropdownItem>
            <VDropdownItem @click="emit('edit', node)">编辑</VDropdownItem>
            <VDropdownItem type="danger" @click="emit('delete', node)">删除</VDropdownItem>
          </template>
        </VDropdown>
      </div>

      <div v-if="node.children.length && expanded.has(node.doc.metadata.name)" class="doc-tree-children">
        <DocTreeNode
          :nodes="node.children"
          :dragging-name="draggingName"
          :over-name="overName"
          :over-zone="overZone"
          :expanded="expanded"
          @toggle="emit('toggle', $event)"
          @open="emit('open', $event)"
          @create-child="emit('create-child', $event)"
          @edit="emit('edit', $event)"
          @delete="emit('delete', $event)"
          @node-dragstart="emit('node-dragstart', $event)"
          @node-dragend="emit('node-dragend')"
          @node-dragover="emit('node-dragover', $event)"
          @node-drop="emit('node-drop')"
        />
      </div>
    </li>
  </ul>
</template>

<style scoped>
.doc-tree-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.doc-tree-children {
  padding-left: 24px;
}
.doc-tree-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  border: 1px solid transparent;
  user-select: none;
}
.doc-tree-row:hover {
  background-color: #f9fafb;
}
.doc-tree-handle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  color: #d1d5db;
  cursor: grab;
  flex-shrink: 0;
}
.doc-tree-handle:hover {
  color: #6b7280;
}
.doc-tree-handle:active {
  cursor: grabbing;
}
.doc-tree-row.is-dragging {
  opacity: 0.4;
}
.doc-tree-row.over-inside {
  background-color: #ddf2f9;
  border-color: #00d9ff;
}
.doc-tree-row.over-before {
  border-top-color: #00d9ff;
}
.doc-tree-row.over-after {
  border-bottom-color: #00d9ff;
}
.doc-tree-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  color: #9ca3af;
  transition: transform 0.15s;
}
.doc-tree-toggle.is-open {
  transform: rotate(90deg);
}
.doc-tree-main {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}
.doc-tree-title {
  font-size: 14px;
  font-weight: 500;
  color: #111827;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
}
.doc-tree-title:hover {
  color: #4f46e5;
  text-decoration: underline;
}
.doc-tree-slug {
  font-size: 12px;
  color: #6b7280;
  white-space: nowrap;
}
.doc-tree-more {
  cursor: pointer;
  color: #6b7280;
}
.doc-tree-more:hover {
  color: #111827;
}
</style>
