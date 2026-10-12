<script setup lang="ts">
defineProps<{modelValue:string;options:{id:string;name:string}[];label:string}>();
const emit=defineEmits<{'update:modelValue':[value:string]}>();
</script>
<template>
 <!-- #ifdef H5 -->
 <select class="analytics-select" :value="modelValue" :aria-label="label" @change="emit('update:modelValue',($event.target as HTMLSelectElement).value)"><option v-for="option in options" :key="option.id" :value="option.id">{{option.name}}</option></select>
 <!-- #endif -->
 <!-- #ifndef H5 -->
 <picker :range="options.map(option=>option.name)" :value="Math.max(0,options.findIndex(option=>option.id===modelValue))" :aria-label="label" @change="emit('update:modelValue',options[Number($event.detail.value)]?.id || modelValue)"><view class="analytics-select">{{options.find(option=>option.id===modelValue)?.name || '请选择'}} ▾</view></picker>
 <!-- #endif -->
</template>
