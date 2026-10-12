<script setup lang="ts">
import ServiceMetrics from './ServiceMetrics.vue';
import SupportManager from './SupportManager.vue';
import {familyAccess} from '../platform/familyAccess';
import { ref } from 'vue';
const section = ref('start'),metricsOpen=ref(false);
const guides = [
  { id:'start', title:'第一次一起沟通', steps:['选择使用人、场景和参与者，开始一次沟通。没建档也可以用儿童或成人预设。','让使用人自己指图或选择；需要协助时注明「家人协助」。留出等待时间，不替本人确定意思。','按分类找卡片，按表达顺序选择，最多20张。也可以直接展示原始选卡。','联网扩写最多给3个候选。本人选择、修改或拒绝后，再主动播放；不合适就回到选卡。','家人可记录「理解了」「需要再说明」「没理解」；没有记录不等于理解。结束后可以查看历史。'] },
  { id:'respect', title:'尊重本人表达', steps:['「不」「不要」「停」「我疼」是完整而重要的表达；不要为了礼貌而弱化。','兴趣、性格和场景只能作为背景，不表示此刻想要什么。','重复选同一张卡片也可能有意义。防误触是可选设置，应按本人操作习惯调整。','先确认表达，再确认听者理解。播放成功并不表示沟通成功。'] },
  { id:'sound', title:'声音与离线', steps:['关闭拟人合成时，Web 使用设备中文声音；实际音色由系统和浏览器提供。','微信体验版不能直接朗读任意新句子，可使用已准备好的卡片声音和文字，或联网使用讯飞。','断网不生成新扩写。保留原始选卡与文字，使用可用的设备声音或已准备音频。','声音失败时继续展示文字；不要把失败记录当作本人表达错误。'] },
  { id:'data', title:'资料、历史与分析', steps:['微信体验和当前本机版本的数据保存在当前设备。清理浏览器或微信数据可能移除记录。','在设置导出 JSON 备份。导入会替换当前数据，需要预览和确认；请先备份现有内容。','照片、家人长期观察和历史正文不会随一次扩写上传；扩写只发送所选卡片及必要背景。','分析反映记录范围内的频次和反馈，不能用于诊断、能力评级或推断本人性格。报告文件不能用于恢复数据。'] },
];
</script>
<template>
  <view class="help-content"><text class="modal-copy">给使用人和家人的简短示范。没有唯一正确的表达方式，按本人习惯一起尝试。</text><view class="help-tabs scene-options"><button v-for="g in guides" :key="g.id" role="button" tabindex="0" :class="{active:section===g.id}" @click="section=g.id">{{g.title}}</button></view><view v-for="g in guides.filter(g=>g.id===section)" :key="g.id"><text class="section-title">{{g.title}}</text><view v-for="(step,i) in g.steps" :key="i" class="help-step"><text>{{i+1}}</text><text>{{step}}</text></view></view><text class="modal-copy">本产品帮助表达和回顾沟通，不提供诊断或替代专业评估。</text><SupportManager :family-id="familyAccess.selected.value?.family.id"/><button role="button" tabindex="0" class="text-button" @click="metricsOpen=!metricsOpen">运营服务指标（需授权）</button><ServiceMetrics v-if="metricsOpen"/></view>
</template>
<style scoped>
.help-content{display:flex;flex-direction:column;gap:22px;padding:0 24px 24px;min-width:0}.help-tabs{flex-wrap:wrap;gap:8px}.help-step{display:flex;gap:14px;line-height:1.8;margin-top:18px;font-size:15px}.help-step>text:first-child{flex:none;display:flex;align-items:center;justify-content:center;width:28px;height:28px;border-radius:50%;background:#edf3f9;color:#163e73;font-size:13px}.help-step>text:last-child{flex:1;min-width:0}
</style>
