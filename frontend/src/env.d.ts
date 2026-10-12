/// <reference types="vite/client" />
declare const wx: { env: { USER_DATA_PATH: string } };
declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
  export default component;
}
