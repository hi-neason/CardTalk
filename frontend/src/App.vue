<script setup lang="ts">
import './styles.css';
// #ifdef H5
if (import.meta.env.PROD && typeof navigator !== 'undefined' && 'serviceWorker' in navigator) {
  void navigator.serviceWorker.register('/service-worker.js').catch(() => { /* Offline caching is optional; basic online communication remains available. */ });
}
// #endif
</script>
<style>
page { background: #f5f7fb; }
</style>
