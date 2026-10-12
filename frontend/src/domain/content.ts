import type { Card } from './types'

export const PRESETS = [
  { id: 'children', name: '儿童体验', description: '用卡片说出想法，家人一起听。' },
  { id: 'adult', name: '成人体验', description: '按自己的节奏，表达需求与感受。' },
] as const
export const SCENES = [
  { id: 'none', name: '不限场景', description: '日常面对面沟通，不额外推断所在地点。' },
  { id: 'bedroom', name: '卧室', description: '休息、睡眠、穿衣与身体感受。' },
  { id: 'kitchen', name: '厨房', description: '准备食物、饮水、吃饭与选择食物。' },
  { id: 'park', name: '公园', description: '户外活动、散步、玩耍与休息。' },
  { id: 'classroom', name: '课堂', description: '学习、参与活动、提出问题与寻求帮助。' },
] as const
export const CATEGORIES = [
  { id: 'people', name: '人物', icon: 'people' },
  { id: 'actions', name: '动作', icon: 'actions' },
  { id: 'fruit', name: '水果', icon: 'fruit' },
  { id: 'food', name: '饮食', icon: 'food' },
  { id: 'feelings', name: '感受', icon: 'feelings' },
  { id: 'transport', name: '交通', icon: 'transport' },
  { id: 'daily', name: '生活', icon: 'daily' },
  { id: 'words', name: '其他词语', icon: 'words' },
] as const
const groups: Record<string, [string, string][]> = {
  people: [['i','我'],['you','你'],['mom','妈妈'],['dad','爸爸'],['grandma','奶奶'],['grandpa','爷爷'],['teacher','老师'],['friend','朋友']],
  actions: [['want','想要'],['eat','吃'],['drink','喝'],['go','去'],['help','帮忙'],['again','再来'],['not','不'],['stop','停'],['look','看'],['listen','听'],['play','玩'],['rest','休息']],
  fruit: [['apple','苹果'],['banana','香蕉'],['orange','橙子'],['grape','葡萄'],['watermelon','西瓜'],['strawberry','草莓']],
  food: [['water','水'],['rice','米饭'],['noodles','面条'],['bread','面包'],['milk','牛奶'],['egg','鸡蛋']],
  feelings: [['happy','开心'],['sad','难过'],['angry','生气'],['afraid','害怕'],['pain','疼'],['tired','累'],['hungry','饿'],['thirsty','渴'],['cold','冷'],['hot','热']],
  transport: [['bus','公交车'],['car','汽车'],['bike','自行车'],['train','火车'],['walk','走路']],
  daily: [['toilet','厕所'],['bed','床'],['book','书'],['ball','球'],['clothes','衣服'],['phone','手机'],['home','家'],['outside','外面']],
  words: [['yes','是'],['no','不是'],['more','多一点'],['less','少一点'],['now','现在'],['later','等一下'],['this','这个'],['that','那个']],
}
export const CARDS: Card[] = Object.entries(groups).flatMap(([category, entries]) => entries.map(([id, label]) => ({ id, label, category, icon: id, kind: 'word' as const })))
export const CORE_CARDS: Card[] = [
  ['core-yes','是','yes'],['core-no','不是','no'],['core-refuse','不要','not'],['core-help','帮帮我','help'],['core-stop','停一下','stop'],['core-pain','我疼','pain'],['core-repeat','再说一遍','again'],
].map(([id, label, icon]) => ({ id: id!, label: label!, icon: icon!, category: 'core', kind: 'phrase' }))
