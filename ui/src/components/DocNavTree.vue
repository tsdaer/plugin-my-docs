<script setup lang="ts">
import RiArrowRightSLine from '~icons/ri/arrow-right-s-line'
import type { DocTreeNode } from '@/utils/doc-tree'

defineProps<{
  nodes: DocTreeNode[]
  // 当前正在编辑的文档 name，用于高亮。
  activeName: string
  expanded: Set<string>
}>()

const emit = defineEmits<{
  (e: 'toggle', name: string): void
  (e: 'select', name: string): void
}>()
</script>

<template>
  <ul class="doc-nav-list">
    <li v-for="node in nodes" :key="node.doc.metadata.name">
      <div
        class="doc-nav-row"
        :class="{ 'is-active': activeName === node.doc.metadata.name }"
        @click="emit('select', node.doc.metadata.name)"
      >
        <button
          class="doc-nav-toggle"
          :class="{ 'is-open': expanded.has(node.doc.metadata.name) }"
          :style="{ visibility: node.children.length ? 'visible' : 'hidden' }"
          type="button"
          @click.stop="emit('toggle', node.doc.metadata.name)"
        >
          <RiArrowRightSLine />
        </button>
        <span class="doc-nav-title">{{ node.doc.spec.title }}</span>
      </div>

      <div
        v-if="node.children.length && expanded.has(node.doc.metadata.name)"
        class="doc-nav-children"
      >
        <DocNavTree
          :nodes="node.children"
          :active-name="activeName"
          :expanded="expanded"
          @toggle="emit('toggle', $event)"
          @select="emit('select', $event)"
        />
      </div>
    </li>
  </ul>
</template>

<style scoped>
.doc-nav-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.doc-nav-children {
  padding-left: 16px;
}
.doc-nav-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 8px;
  border-radius: 4px;
  cursor: pointer;
  user-select: none;
}
.doc-nav-row:hover {
  background-color: #f3f4f6;
}
.doc-nav-row.is-active {
  background-color: #eef2ff;
}
.doc-nav-row.is-active .doc-nav-title {
  color: #4f46e5;
  font-weight: 600;
}
.doc-nav-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  color: #9ca3af;
  transition: transform 0.15s;
  flex-shrink: 0;
}
.doc-nav-toggle.is-open {
  transform: rotate(90deg);
}
.doc-nav-title {
  font-size: 13px;
  color: #374151;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
