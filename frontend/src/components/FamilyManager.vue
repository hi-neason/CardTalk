<script setup lang="ts">
// #ifdef APP
import {readNativeImage} from '../platform/nativeImage';
// #endif
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { CARDS, CORE_CARDS, CATEGORIES, SCENES, createFamilyProfile, updateFamilyProfile, archiveFamilyProfile, createCustomCard, updateCustomCard, archiveCustomCard, createCustomScene, updateCustomScene, archiveCustomScene, getCardLabelWarning, type State, type Card, type FamilyProfile, type CustomCard, type CustomScene, type Preset, type CommunicationPreferences, getCommunicationPreferences } from '../domain'
import {isCardImage} from '../domain/family-validation';
import '../family.css'
import CommunicationPreferencesEditor from './CommunicationPreferences.vue'

type Tab = 'profiles' | 'cards' | 'scenes'
const props = defineProps<{ state: State; capabilities: { profileManagement: boolean; scenarioManagement: boolean; customCards: boolean }; tab?: Tab; persist?: (state: State) => boolean }>()
const emit = defineEmits<{ change: [state: State]; close: []; preview: [card: Card] }>()
const tab = ref<Tab>(props.tab || 'profiles')
const search = ref('')
const showArchived = ref(false)
const editing = ref(false)
const editId = ref<string | null>(null)
const error = ref('')
const notice = ref('')
const iconPicker = ref(false)
const iconSearch = ref('')
const recommendationSearch = ref('')
const uploading = ref(false)
let uploadGeneration = 0
onBeforeUnmount(() => { uploadGeneration++ })
watch(error, async value => {
  if (!value) return
  await nextTick()
  // #ifdef H5
  document.querySelector('.family-manager .family-error')?.scrollIntoView({ block: 'nearest' })
  // #endif
})
const tabs: { id: Tab; name: string }[] = [{ id: 'profiles', name: '使用人' }, { id: 'cards', name: '自定义卡片' }, { id: 'scenes', name: '场景' }]
const enabled = computed(() => tab.value === 'profiles' ? props.capabilities.profileManagement : tab.value === 'cards' ? props.capabilities.customCards : props.capabilities.scenarioManagement)
const noun = computed(() => tab.value === 'profiles' ? '使用人' : tab.value === 'cards' ? '卡片' : '场景')
const profilePreferences = ref<CommunicationPreferences>(getCommunicationPreferences(props.state))
const profile = reactive({ image: '', name: '', preset: 'children' as Preset, age: '', language: '普通话', interests: '', personality: '', communicationStyle: '', observations: '', observer: '', observedAt: '' })
const card = reactive({ label: '', description: '', category: 'daily', icon: 'home', kind: 'word' as Card['kind'], image: '', sceneIds: [] as string[] })
const scene = reactive({ image: '', name: '', description: '', activities: '', objects: '', recommendedCardIds: [] as string[] })
const profiles = computed(() => props.state.family.profiles.filter(item => (showArchived.value || !item.archived) && item.name.toLowerCase().includes(search.value.trim().toLowerCase())))
const cards = computed(() => props.state.family.cards.filter(item => (showArchived.value || !item.archived) && `${item.label} ${item.description || ''}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const scenes = computed(() => props.state.family.scenes.filter(item => (showArchived.value || !item.archived) && `${item.name} ${item.description}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const count = computed(() => tab.value === 'profiles' ? profiles.value.length : tab.value === 'cards' ? cards.value.length : scenes.value.length)
const availableScenes = computed(() => [...SCENES.filter(item => item.id !== 'none'), ...props.state.family.scenes.filter(item => !item.archived || card.sceneIds.includes(item.id))])
const availableCards = computed(() => [...CORE_CARDS, ...CARDS, ...props.state.family.cards.filter(item => !item.archived || scene.recommendedCardIds.includes(item.id))].filter(item => item.label.includes(recommendationSearch.value.trim())))
const labelWarning = computed(() => getCardLabelWarning(card.label))
const icons = computed(() => CARDS.filter(item => item.label.includes(iconSearch.value.trim())))
const art = (item: Card) => item.image || `/static/cards/${item.icon}.svg`
const categoryName = (id: string) => CATEGORIES.find(item => item.id === id)?.name || id
function cancel() { editing.value = false; editId.value = null; error.value = ''; iconPicker.value = false; uploading.value = false; uploadGeneration++ }
function switchTab(next: Tab) { cancel(); tab.value = next; search.value = ''; notice.value = '' }
watch(() => props.tab, value => { if (value && value !== tab.value) switchTab(value) })
function begin(item?: FamilyProfile | CustomCard | CustomScene) {
  cancel(); notice.value = ''; editId.value = item?.id || null; editing.value = true
  if (tab.value === 'profiles') {
    const value = item as FamilyProfile | undefined
    profilePreferences.value = { ...(value?.preferences || getCommunicationPreferences(props.state)) }
    Object.assign(profile, { image: value?.image || '', name: value?.name || '', preset: value?.preset || 'children', age: value?.age == null ? '' : String(value.age), language: value?.language ?? '普通话', interests: value?.interests || '', personality: value?.personality || '', communicationStyle: value?.communicationStyle || '', observations: value?.observations || '', observer: value?.observer || '', observedAt: value?.observedAt?.slice(0, 10) || '' })
  } else if (tab.value === 'cards') {
    const value = item as CustomCard | undefined
    Object.assign(card, { label: value?.label || '', description: value?.description || '', category: value?.category || 'daily', icon: value?.icon || 'home', kind: value?.kind || 'word', image: value?.image || '', sceneIds: [...(value?.sceneIds || [])] })
  } else {
    const value = item as CustomScene | undefined
    Object.assign(scene, { image: value?.image || '', name: value?.name || '', description: value?.description || '', activities: value?.activities || '', objects: value?.objects || '', recommendedCardIds: [...(value?.recommendedCardIds || [])] })
    recommendationSearch.value = ''
  }
}
function toggle(values: string[], id: string) {
  if (!enabled.value) return
  const index = values.indexOf(id)
  if (index >= 0) { values.splice(index, 1); return }
  const max = tab.value === 'scenes' ? 70 : 50
  if (values.length >= max) { error.value = `最多选择 ${max} 项，请先移除一项。`; return }
  values.push(id)
}
function persist(next: State) {
  if (props.persist) { if (!props.persist(next)) throw new Error('未保存，请检查本机存储并重试。') }
  else emit('change', next)
}
function save() {
  if (!enabled.value || uploading.value) return
  error.value = ''
  try {
    let next: State
    if (tab.value === 'profiles') {
      const age = profile.age.trim() === '' ? null : Number(profile.age)
      if (age !== null && (!Number.isInteger(age) || age < 0 || age > 120)) throw new Error('年龄请填写 0 至 120 之间的整数，也可以留空。')
      if (!profile.name.trim()) throw new Error('请填写使用人姓名。')
      if (profile.observedAt && (!/^\d{4}-\d{2}-\d{2}$/.test(profile.observedAt) || !Number.isFinite(Date.parse(profile.observedAt)) || new Date(profile.observedAt).toISOString().slice(0, 10) !== profile.observedAt)) throw new Error('请填写有效的观察日期，格式为 YYYY-MM-DD。')
      if (profile.observations.trim() && (!profile.observer.trim() || !profile.observedAt)) throw new Error('填写家人观察时，请同时填写记录人和观察日期。')
      const input = { ...profile, preferences: { ...profilePreferences.value }, age, observedAt: profile.observedAt || null }
      next = editId.value ? updateFamilyProfile(props.state, editId.value, input) : createFamilyProfile(props.state, input)
    } else if (tab.value === 'cards') {
      if (!card.label.trim()) throw new Error('请填写卡片名称。')
      const input = { ...card, image: card.image || (editId.value ? '' : undefined), sceneIds: [...card.sceneIds] }
      next = editId.value ? updateCustomCard(props.state, editId.value, input) : createCustomCard(props.state, input)
    } else {
      if (!scene.name.trim() || !scene.description.trim()) throw new Error('请填写场景名称和场景描述。')
      const input = { ...scene, recommendedCardIds: [...scene.recommendedCardIds] }
      next = editId.value ? updateCustomScene(props.state, editId.value, input) : createCustomScene(props.state, input)
    }
    persist(next); cancel(); notice.value = `已保存${noun.value}。正在进行的沟通与历史记录保留原来的资料。`
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '未能保存，请检查填写内容。' }
}
async function archive(item: FamilyProfile | CustomCard | CustomScene) {
  if (!enabled.value) return
  error.value = ''; notice.value = ''
  const restore = item.archived
  if (!restore) {
    const result = await uni.showModal({ title: `归档${noun.value}`, content: '归档后不再出现在新沟通的选择列表中，已有沟通与历史记录保留。之后可以恢复。', confirmText: '归档', cancelText: '取消' })
    if (!result.confirm) return
  }
  try {
    const next = tab.value === 'profiles' ? archiveFamilyProfile(props.state, item.id, !restore) : tab.value === 'cards' ? archiveCustomCard(props.state, item.id, !restore) : archiveCustomScene(props.state, item.id, !restore)
    persist(next); notice.value = restore ? '已恢复。' : '已归档，可在“显示已归档”中恢复。'
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '操作未完成，请重试。' }
}
function preview() { if (!card.label.trim()) { error.value = '请先填写卡片名称。'; return }; emit('preview', { id: editId.value || 'preview', ...card }) }
function uploadImage(target: Tab = 'cards') {
  if (!enabled.value) return
  // #ifdef H5
  const destination = target === 'profiles' ? profile : target === 'scenes' ? scene : card
  const generation = ++uploadGeneration
  const input = document.createElement('input')
  input.type = 'file'; input.accept = 'image/png,image/jpeg,image/webp'
  input.onchange = async () => {
    const file = input.files?.[0]
    if (!file || generation !== uploadGeneration || tab.value !== target || !editing.value || !enabled.value) return
    uploading.value = true; error.value = ''
    try {
      if (file.size === 0) throw new Error('请选择非空的图片。')
      const bytes = new Uint8Array(await file.slice(0, 12).arrayBuffer())
      const png = bytes[0] === 137 && bytes[1] === 80 && bytes[2] === 78 && bytes[3] === 71
      const jpeg = bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255
      const webp = String.fromCharCode(...bytes.slice(0, 4)) === 'RIFF' && String.fromCharCode(...bytes.slice(8, 12)) === 'WEBP'
      const mime = png ? 'image/png' : jpeg ? 'image/jpeg' : webp ? 'image/webp' : ''
      if (!mime || file.type !== mime) throw new Error('图片实际格式必须是 PNG、JPEG 或 WebP，不支持 SVG 或改后缀的文件。')
      const data = await new Promise<string>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = () => reject(new Error('无法读取图片，请重新选择。')); reader.readAsDataURL(file) })
      await new Promise<void>((resolve, reject) => { const image = new Image(); image.onload = () => { if (image.naturalWidth > 1024 || image.naturalHeight > 1024 || !image.naturalWidth || !image.naturalHeight) reject(new Error('图片宽和高均不能超过 1024 像素，请缩小后重试。')); else resolve() }; image.onerror = () => reject(new Error('图片无法打开，请换一张完整的图片。')); image.src = data })
      if (generation === uploadGeneration) destination.image = data
    } catch (reason) { if (generation === uploadGeneration) error.value = reason instanceof Error ? reason.message : '图片读取失败。' }
    finally { if (generation === uploadGeneration) uploading.value = false }
  }
  input.click()
  // #endif
  // #ifdef APP
  const nativeDestination=target==='profiles'?profile:target==='scenes'?scene:card;
  const nativeGeneration=++uploadGeneration;
  uni.chooseImage({count:1,sourceType:['album','camera'],success:async result=>{
    if(nativeGeneration!==uploadGeneration||tab.value!==target||!editing.value||!enabled.value)return;
    uploading.value=true;error.value='';
    try{const path=result.tempFilePaths[0];if(!path)throw new Error('未选择图片');const data=await readNativeImage(path);if(!isCardImage(data))throw new Error('图片格式、完整性或尺寸不符合要求，请换一张图片。');if(nativeGeneration===uploadGeneration&&enabled.value)nativeDestination.image=data;}
    catch(reason){if(nativeGeneration===uploadGeneration)error.value=reason instanceof Error?reason.message:'图片读取失败';}
    finally{if(nativeGeneration===uploadGeneration)uploading.value=false;}
  },fail:()=>{if(nativeGeneration===uploadGeneration)error.value='未选择图片，已有照片保留。';}});
  // #endif

}
</script>

<template>
  <view class="family-manager">
    <view class="family-tabs" aria-label="家庭管理分类">
      <button role="button" tabindex="0" v-for="item in tabs" :key="item.id" :class="{ active: tab === item.id }" :aria-pressed="tab === item.id" @click="switchTab(item.id)">{{ item.name }}</button>
    </view>
    <view class="family-intro"><text class="family-kicker">让熟悉的人和事，走进每一次表达</text><text>资料保存在本机。扩写时只使用选中的使用人及场景信息。</text></view>
    <view v-if="!enabled" class="family-message">此功能尚未开启，已有资料可查看。使用人、场景与分析分别开启，不影响基础沟通。</view>
    <view v-if="error && !editing" class="family-message family-error" role="alert">{{ error }}</view>
    <view v-if="notice" class="family-message" role="status">{{ notice }}</view>
    <template v-if="!editing">
      <view class="family-toolbar"><input v-model="search" :aria-label="`搜索${noun}`" :placeholder="`搜索${noun}`" maxlength="100" /><button role="button" tabindex="0" class="primary-button" :disabled="!enabled" @click="begin()">新增{{ noun }}</button></view>
      <view class="family-list-meta"><text>{{ count }} 项</text><button role="button" tabindex="0" :aria-pressed="showArchived" :class="{ active: showArchived }" @click="showArchived = !showArchived">{{ showArchived ? '隐藏已归档' : '显示已归档' }}</button></view>
      <view v-if="count === 0" class="family-empty"><text>{{ search ? '没有找到匹配的资料' : `还没有自定义${noun}` }}</text><text>{{ search ? '试试其他关键词，或显示已归档资料。' : `添加常用的${noun}，下一次创建沟通时就能选择。` }}</text></view>
      <template v-if="tab === 'profiles'">
        <view v-for="item in profiles" :key="item.id" class="family-row" :class="{ archived: item.archived }">
          <image v-if="item.image" class="family-thumb" :src="item.image" mode="aspectFit" :aria-label="`${item.name}图片`"/><view v-else class="family-avatar">{{ item.name.slice(0, 1) }}</view><view class="family-row-info"><text class="family-row-title">{{ item.name }} <text v-if="item.archived" class="family-badge">已归档</text></text><text>{{ item.preset === 'children' ? '儿童' : '成人' }} · {{ item.age === null ? '年龄未填写' : `${item.age} 岁` }} · {{ item.language || '语言未填写' }}</text><text v-if="item.interests" class="family-row-note">喜欢：{{ item.interests }}</text></view>
          <view class="family-row-actions"><button role="button" tabindex="0" :aria-label="`${enabled ? '编辑' : '查看'}使用人 ${item.name}`" @click="begin(item)">{{ enabled ? '编辑' : '查看' }}</button><button role="button" tabindex="0" v-if="enabled" :aria-label="`${item.archived ? '恢复' : '归档'}使用人 ${item.name}`" @click="archive(item)">{{ item.archived ? '恢复' : '归档' }}</button></view>
        </view>
      </template>
      <template v-else-if="tab === 'cards'">
        <view v-for="item in cards" :key="item.id" class="family-row" :class="{ archived: item.archived }"><image class="family-thumb" :src="art(item)" mode="aspectFit" /><view class="family-row-info"><text class="family-row-title">{{ item.label }} <text v-if="item.archived" class="family-badge">已归档</text></text><text>{{ categoryName(item.category) }} · {{ item.kind === 'phrase' ? '整句' : '词语' }}</text><text v-if="item.description" class="family-row-note">{{ item.description }}</text></view><view class="family-row-actions"><button role="button" tabindex="0" :aria-label="`${enabled ? '编辑' : '查看'}卡片 ${item.label}`" @click="begin(item)">{{ enabled ? '编辑' : '查看' }}</button><button role="button" tabindex="0" v-if="enabled" :aria-label="`${item.archived ? '恢复' : '归档'}卡片 ${item.label}`" @click="archive(item)">{{ item.archived ? '恢复' : '归档' }}</button></view></view>
      </template>
      <template v-else>
        <view v-for="item in scenes" :key="item.id" class="family-row" :class="{ archived: item.archived }"><image v-if="item.image" class="family-thumb" :src="item.image" mode="aspectFit" :aria-label="`${item.name}图片`"/><view v-else class="family-avatar">{{ item.name.slice(0, 1) }}</view><view class="family-row-info"><text class="family-row-title">{{ item.name }} <text v-if="item.archived" class="family-badge">已归档</text></text><text class="family-row-note">{{ item.description }}</text><text>{{ item.recommendedCardIds.length }} 张推荐卡片</text></view><view class="family-row-actions"><button role="button" tabindex="0" :aria-label="`${enabled ? '编辑' : '查看'}场景 ${item.name}`" @click="begin(item)">{{ enabled ? '编辑' : '查看' }}</button><button role="button" tabindex="0" v-if="enabled" :aria-label="`${item.archived ? '恢复' : '归档'}场景 ${item.name}`" @click="archive(item)">{{ item.archived ? '恢复' : '归档' }}</button></view></view>
        <view class="family-builtins"><text class="family-field-title">内置场景</text><text>{{ SCENES.map(item => item.name).join(' · ') }}</text><text>内置场景可直接使用；自定义场景可补充活动、物品与常用卡片。</text></view>
      </template>
    </template>
    <view v-else class="family-editor">
      <view class="family-editor-heading"><text>{{ enabled ? (editId ? '编辑' : '新增') : '查看' }}{{ noun }}</text><button role="button" tabindex="0" @click="cancel">返回列表</button></view>
      <template v-if="tab === 'profiles'">
        <view class="family-field"><text>使用人姓名 *</text><input v-model="profile.name" aria-label="使用人姓名" :disabled="!enabled" maxlength="40" placeholder="平时怎么称呼 TA" /></view>
        <view class="family-field"><text>使用人图片</text><view class="family-picture-row"><image v-if="profile.image" :src="profile.image" class="family-preview-art" mode="aspectFit" aria-label="使用人图片预览"/><view v-else class="family-avatar">{{ profile.name.slice(0,1) || '＋' }}</view><view><button role="button" tabindex="0" :disabled="!enabled || uploading" class="quiet-button" @click="uploadImage('profiles')">{{ uploading ? '正在读取图片…' : '上传使用人图片' }}</button><button role="button" tabindex="0" v-if="profile.image" :disabled="!enabled || uploading" class="text-button" @click="profile.image = ''">移除使用人图片</button></view></view><text class="family-hint">PNG、JPEG 或 WebP；宽高不超过 1024 像素。仅保存在本机，不发送给 AI。</text></view><view class="family-field"><text>沟通预设</text><view class="family-choices"><button role="button" tabindex="0" :disabled="!enabled" :class="{ active: profile.preset === 'children' }" @click="profile.preset = 'children'">儿童</button><button role="button" tabindex="0" :disabled="!enabled" :class="{ active: profile.preset === 'adult' }" @click="profile.preset = 'adult'">成人</button></view></view>
        <view class="family-two-columns"><view class="family-field"><text>年龄（选填）</text><input v-model="profile.age" aria-label="使用人年龄" :disabled="!enabled" type="number" maxlength="3" placeholder="如 8" /></view><view class="family-field"><text>常用语言</text><input v-model="profile.language" aria-label="常用语言" :disabled="!enabled" maxlength="80" placeholder="如 普通话" /></view></view>
        <view class="family-field"><text>兴趣与喜好</text><textarea v-model="profile.interests" aria-label="兴趣与喜好" :disabled="!enabled" maxlength="500" placeholder="如 喜欢苹果、散步，最喜欢蓝色" /></view>
        <view class="family-field"><text>性格与交流节奏</text><textarea v-model="profile.personality" aria-label="性格与交流节奏" :disabled="!enabled" maxlength="500" placeholder="如 需要多一点等待时间，喜欢简短清楚的问句" /></view>
        <view class="family-field"><text>表达习惯</text><textarea v-model="profile.communicationStyle" aria-label="表达习惯" :disabled="!enabled" maxlength="500" placeholder="如 喜欢先说想要的东西，再说动作" /></view>
        <view class="profile-preferences"><text class="family-field-title">个人沟通偏好</text><text class="family-hint">保存后用于新建的沟通，已有沟通继续使用当时的偏好。</text><button role="button" tabindex="0" class="quiet-button" :disabled="!enabled" @click="profilePreferences = { ...getCommunicationPreferences(state) }">采用当前全局设置</button><CommunicationPreferencesEditor v-model="profilePreferences" :preset="profile.preset" :disabled="!enabled" /></view>
        <view class="family-section-note">以下是家人的观察记录，单独保存在本机，不作为使用者已经确认的表达。</view>
        <view class="family-field"><text>家人观察</text><textarea v-model="profile.observations" aria-label="家人观察" :disabled="!enabled" maxlength="1000" placeholder="记录具体发生的事情，避免替 TA 下结论" /></view>
        <view class="family-two-columns"><view class="family-field"><text>记录人（有观察时必填）</text><input v-model="profile.observer" aria-label="观察记录人" :disabled="!enabled" maxlength="40" placeholder="如 妈妈" /></view><view class="family-field"><text>观察日期（有观察时必填）</text><input v-model="profile.observedAt" aria-label="观察日期" :disabled="!enabled" maxlength="10" placeholder="YYYY-MM-DD" /></view></view>
      </template>
      <template v-else-if="tab === 'cards'">
        <view class="family-field"><text>卡片名称 *</text><input v-model="card.label" aria-label="卡片名称" :disabled="!enabled" maxlength="50" placeholder="如 小熊，或我想休息一下" /><text v-if="labelWarning" class="family-hint">{{ labelWarning }}</text></view>
        <view class="family-field"><text>含义说明</text><textarea v-model="card.description" aria-label="卡片含义说明" :disabled="!enabled" maxlength="500" placeholder="如 小熊是家里的毛绒玩具，不是动物" /></view>
        <view class="family-field"><text>分类</text><view class="family-choices"><button role="button" tabindex="0" v-for="item in CATEGORIES" :key="item.id" :disabled="!enabled" :class="{ active: card.category === item.id }" @click="card.category = item.id">{{ item.name }}</button></view></view>
        <view class="family-field"><text>表达类型</text><view class="family-choices"><button role="button" tabindex="0" :disabled="!enabled" :class="{ active: card.kind === 'word' }" @click="card.kind = 'word'">词语</button><button role="button" tabindex="0" :disabled="!enabled" :class="{ active: card.kind === 'phrase' }" @click="card.kind = 'phrase'">整句</button></view></view>
        <view class="family-field"><text>卡片图片</text><view class="family-picture-row"><image :src="art({ id: 'preview', ...card })" class="family-preview-art" mode="aspectFit" /><view><button role="button" tabindex="0" :disabled="!enabled" class="quiet-button" @click="iconPicker = !iconPicker">选择内置图案</button><button role="button" tabindex="0" :disabled="!enabled || uploading" class="quiet-button" @click="uploadImage('cards')">{{ uploading ? '正在读取图片…' : '上传本机图片' }}</button><button role="button" tabindex="0" v-if="card.image" :disabled="!enabled" class="text-button" @click="card.image = ''">移除照片</button></view></view><text class="family-hint">PNG、JPEG 或 WebP；宽高不超过 1024 像素。照片仅保存在本机。</text>
          <view v-if="iconPicker" class="family-icon-picker"><input v-model="iconSearch" aria-label="搜索内置图案" maxlength="40" placeholder="搜索图案名称" /><view class="family-icons"><button role="button" tabindex="0" v-for="item in icons" :key="item.id" :disabled="!enabled" :class="{ active: card.icon === item.icon && !card.image }" :aria-label="`使用${item.label}图案`" @click="card.icon = item.icon; card.image = ''; iconPicker = false"><image :src="art(item)" mode="aspectFit" /><text>{{ item.label }}</text></button></view></view>
        </view>
        <view class="family-field"><text>适用场景（选填）</text><view class="family-choices"><button role="button" tabindex="0" v-for="item in availableScenes" :key="item.id" :disabled="!enabled" :class="{ active: card.sceneIds.includes(item.id) }" :aria-pressed="card.sceneIds.includes(item.id)" @click="toggle(card.sceneIds, item.id)">{{ item.name }}</button></view><text class="family-hint">可多选；不选择时适用于所有场景。</text></view>
        <button role="button" tabindex="0" class="quiet-button" @click="preview">预览设备发声</button><text class="family-hint">预览使用设备声音；不调用付费语音合成。</text>
      </template>
      <template v-else>
        <view class="family-field"><text>场景名称 *</text><input v-model="scene.name" aria-label="场景名称" :disabled="!enabled" maxlength="40" placeholder="如 奶奶家、康复训练室" /></view>
        <view class="family-field"><text>场景图片</text><view class="family-picture-row"><image v-if="scene.image" :src="scene.image" class="family-preview-art" mode="aspectFit" aria-label="场景图片预览"/><view v-else class="family-avatar">{{ scene.name.slice(0,1) || '＋' }}</view><view><button role="button" tabindex="0" :disabled="!enabled || uploading" class="quiet-button" @click="uploadImage('scenes')">{{ uploading ? '正在读取图片…' : '上传场景图片' }}</button><button role="button" tabindex="0" v-if="scene.image" :disabled="!enabled || uploading" class="text-button" @click="scene.image = ''">移除场景图片</button></view></view><text class="family-hint">PNG、JPEG 或 WebP；宽高不超过 1024 像素。仅保存在本机，不发送给 AI。</text></view><view class="family-field"><text>场景描述 *</text><textarea v-model="scene.description" aria-label="场景描述" :disabled="!enabled" maxlength="500" placeholder="介绍这个地方与常见的沟通情境，帮助扩写理解背景" /></view>
        <view class="family-field"><text>常见活动</text><textarea v-model="scene.activities" aria-label="常见活动" :disabled="!enabled" maxlength="500" placeholder="如 吃点心、看相册、和家人聊天" /></view>
        <view class="family-field"><text>常见物品</text><textarea v-model="scene.objects" aria-label="常见物品" :disabled="!enabled" maxlength="500" placeholder="如 茶杯、相册、沙发" /></view>
        <view class="family-field"><text>推荐卡片 · 已选 {{ scene.recommendedCardIds.length }} 张</text><input v-model="recommendationSearch" aria-label="搜索推荐卡片" maxlength="40" placeholder="搜索卡片名称" /><view class="family-recommendations family-choices"><button role="button" tabindex="0" v-for="item in availableCards" :key="item.id" :disabled="!enabled" :class="{ active: scene.recommendedCardIds.includes(item.id) }" :aria-pressed="scene.recommendedCardIds.includes(item.id)" @click="toggle(scene.recommendedCardIds, item.id)">{{ item.label }}</button></view><text class="family-hint">只用于方便找卡，不会替使用者选卡或表达。</text></view>
      </template>
      <view v-if="error" class="family-message family-error" role="alert">{{ error }}</view>
      <view class="family-form-actions"><button role="button" tabindex="0" class="quiet-button" @click="cancel">{{ enabled ? '取消' : '返回列表' }}</button><button role="button" tabindex="0" v-if="enabled" class="primary-button" :disabled="uploading" @click="save">保存{{ noun }}</button></view>
    </view>
  </view>
</template>
