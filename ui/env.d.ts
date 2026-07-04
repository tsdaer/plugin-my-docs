/// <reference types="@rsbuild/core/types" />
/// <reference types="unplugin-icons/types/vue" />

// FormKit is globally registered by Halo at runtime (not bundled). Declare the
// global `$formkit` helper so vue-tsc recognizes `$formkit.submit(id)` in templates.
declare module 'vue' {
  interface ComponentCustomProperties {
    $formkit: {
      submit: (id: string) => void
    }
  }
}

export {}
