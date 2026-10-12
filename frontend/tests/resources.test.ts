import {describe,it,expect} from 'vitest'
import {createState,createRoom,addCard,recordRawExpression,CARDS} from '../src/domain'
import {validatePublicPackage,installPublicResources,type PublicPackage} from '../src/domain/resources'
const pack:PublicPackage={id:'rehab-v1',title:'日常资源',source:'CardTalk 自编文字',license:'CC0-1.0',cards:[{id:'sip',label:'小口喝水',category:'actions',icon:'drink',kind:'phrase',description:'表达饮水需求',sceneIds:['rehab']}],scenes:[{id:'rehab',name:'康复活动',description:'休息与交流',recommendedCardIds:['sip']}]}
describe('公共资源显式安装',()=>{
 it('拒绝私人图片/观察/未知图标/非法关联和空许可',()=>{expect(validatePublicPackage(pack)).toBe(true);for(const changed of [{...pack,image:'photo'},{...pack,license:''},{...pack,cards:[{...pack.cards[0],observations:'私密'}]},{...pack,cards:[{...pack.cards[0],icon:'remote'}]},{...pack,scenes:[{...pack.scenes[0],recommendedCardIds:['missing']}]}])expect(validatePublicPackage(changed)).toBe(false)})
 it('仅安装明确选择，关联限于同时选择，原有历史快照不变',()=>{let s=createRoom(createState(),{preset:'adult',communicatorName:'本人',sceneId:null,participants:[],source:'unknown'});s=recordRawExpression(addCard(s,CARDS[0]!));const before=JSON.stringify(s),next=installPublicResources(s,pack,{cards:['sip'],scenes:['rehab']});expect(next.rooms).toEqual(s.rooms);expect(JSON.stringify(s)).toBe(before);expect(next.family.cards[0]?.sceneIds).toEqual(['custom-scene-public-rehab']);expect(next.family.scenes[0]?.recommendedCardIds).toEqual(['custom-card-public-sip']);expect(()=>installPublicResources(s,pack,{cards:['sip'],scenes:[]})).toThrow('关联场景')})
 it('拒绝重复版本覆盖和部分失败，使用者的编辑保持不变',()=>{const s=installPublicResources(createState(),pack,{cards:['sip'],scenes:['rehab']});s.family.cards[0]!.label='我的私人饮水说法';const before=JSON.stringify(s);expect(()=>installPublicResources(s,{...pack,id:'rehab-v2'},{cards:['sip'],scenes:['rehab']})).toThrow('已存在');expect(JSON.stringify(s)).toBe(before);expect(()=>installPublicResources(s,pack,{cards:[],scenes:['rehab']})).toThrow('关联卡片')})
 it('无选择和越界选择不写入',()=>{expect(()=>installPublicResources(createState(),pack,{cards:[],scenes:[]})).toThrow();expect(()=>installPublicResources(createState(),pack,{cards:['unknown'],scenes:[]})).toThrow()})
})

import {updateCustomCard,updateCustomScene,endRoom,validateState} from '../src/domain'
import {parseBackup} from '../src/domain/backup'
import {localSyncEntities,stripSyncPrivate} from '../src/domain/sync'
it('公共来源及发布修订保存在卡片场景与历史中，备份/同步保持，后续编辑不改变历史',()=>{
 let s=installPublicResources(createState(),pack,{cards:['sip'],scenes:['rehab']},12)
 const provenance={packageId:pack.id,title:pack.title,source:pack.source,license:pack.license,revision:12}
 expect(s.family.cards[0]!.provenance).toEqual(provenance);expect(s.family.scenes[0]!.provenance).toEqual(provenance)
 s=createRoom(s,{preset:'adult',communicatorName:'本人',sceneId:s.family.scenes[0]!.id,participants:[],source:'unknown'})
 s=endRoom(recordRawExpression(addCard(s,s.family.cards[0]!)))
 const room=JSON.stringify(s.rooms)
 s=updateCustomCard(s,s.family.cards[0]!.id,{label:'我自己的表达'});s=updateCustomScene(s,s.family.scenes[0]!.id,{description:'我自己的描述'})
 expect(s.rooms[0]!.turns[0]!.cardSnapshot[0]).not.toHaveProperty('sceneIds');expect(JSON.stringify(s.rooms)).toBe(room);expect(s.rooms[0]!.turns[0]!.cardSnapshot[0]!.provenance).toEqual(provenance);expect(s.rooms[0]!.turns[0]!.contextSnapshot.sceneSnapshot!.provenance).toEqual(provenance)
 const restored=parseBackup(JSON.stringify(s));expect(restored.family.cards[0]!.provenance).toEqual(provenance)
 const synced=stripSyncPrivate(localSyncEntities(restored),{photos:false,observations:false});expect(synced.find((x:any)=>x.entityType==='card').payload.provenance).toEqual(provenance)
 const corrupted=JSON.parse(JSON.stringify(s));corrupted.family.cards[0].provenance.observations='私密';expect(validateState(corrupted).valid).toBe(false)
 const corruptedSnapshot=JSON.parse(JSON.stringify(s));corruptedSnapshot.rooms[0].turns[0].cardSnapshot[0].provenance.image='photo';expect(validateState(corruptedSnapshot).valid).toBe(false)
})

it('旧自定义卡片快照上传只清管理字段，不影响实体和原始历史',()=>{const s=installPublicResources(createState(),pack,{cards:['sip'],scenes:['rehab']},3),card=s.family.cards[0]!;const legacy={cardSnapshot:[card],draft:{cards:[card]}};const before=JSON.stringify(legacy);const wire=stripSyncPrivate(legacy,{photos:false,observations:false});expect(wire.cardSnapshot[0]).not.toHaveProperty('sceneIds');expect(wire.draft.cards[0]).not.toHaveProperty('archived');expect(wire.cardSnapshot[0].provenance).toEqual(card.provenance);expect(stripSyncPrivate(card,{photos:false,observations:false}).sceneIds).toEqual(card.sceneIds);expect(JSON.stringify(legacy)).toBe(before)})
