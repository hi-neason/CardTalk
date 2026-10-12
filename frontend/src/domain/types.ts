export type Emotion = 'neutral' | 'happy' | 'sad' | 'angry' | 'fearful'
export interface SelectedEmotion { emotion: Emotion; conflict: boolean; selectedEmotions: Exclude<Emotion, 'neutral'>[]; suppressionReason?: 'negation' }
export type Preset = 'children' | 'adult'
export type Source = 'independent' | 'assisted' | 'unknown'
export interface Card { id: string; label: string; category: string; icon: string; kind: 'word' | 'phrase'; description?: string; image?: string }
export interface Context { preset: Preset; sceneId: string | null; participants: { id: string; name: string }[]; communicatorName: string; source: Source; profileId?: string; profileSnapshot?: ProfileSnapshot; sceneSnapshot?: SceneSnapshot; currentState?: string }
export interface Candidate { id: string; text: string; sourceCardIds: string[] }
export interface Expansion { requestId: string; inputVersion: number; candidates: Candidate[]; needsClarification: boolean; clarification: string | null; promptVersion: string; remaining: number }
export type PlaybackStatus = 'started' | 'completed' | 'interrupted' | 'failed' | 'unavailable'
export type FeedbackValue = 'understood' | 'clarify' | 'not-understood' | 'unrecorded'
export interface Turn { id: string; createdAt: string; cardSnapshot: Card[]; contextSnapshot: Context; expansion?: Expansion; finalText: string; selectedCandidateId?: string; source: Source; wasEdited: boolean; kind: 'generated' | 'raw'; playbackEvents: { id: string; status: PlaybackStatus; at: string }[]; feedback: { value: FeedbackValue; by: string; at: string; note?: string } | null }
export interface Room { id: string; context: Context; startedAt: string; endedAt: string | null; status: 'active' | 'ended'; turns: Turn[]; draft: { cards: Card[]; version: number; expansion: Expansion | null; confirmedText: string | null; selectedCandidateId: string | null; confirmedTurnId: string | null } }
export interface State { version: 1; family: Family; rooms: Room[]; activeRoomId: string | null; settings: { fontSize: 'normal' | 'large'; rate: number; preset: Preset; aiExpansionEnabled: boolean; speechSynthesisEnabled: boolean; emotionRecognitionEnabled: boolean } }

export interface FamilyProfile {
  id: string; name: string; preset: Preset; age: number | null; language: string;
  interests: string; personality: string; communicationStyle: string;
  observations: string; observer: string; observedAt: string | null;
  archived: boolean; updatedAt: string;
}
export interface ProfileSnapshot { name: string; age: number | null; language: string; interests: string; personality: string; communicationStyle: string; currentState: string }
export interface SceneSnapshot { id: string; name: string; description: string }
export interface CustomCard extends Card { sceneIds: string[]; archived: boolean; updatedAt: string }
export interface CustomScene { id: string; name: string; description: string; activities: string; objects: string; recommendedCardIds: string[]; archived: boolean; updatedAt: string }
export interface Family { profiles: FamilyProfile[]; cards: CustomCard[]; scenes: CustomScene[] }
export type FamilyProfileInput = Pick<FamilyProfile, 'name' | 'preset'> & Partial<Omit<FamilyProfile, 'id' | 'name' | 'preset' | 'archived' | 'updatedAt'>>
export type CustomCardInput = Pick<Card, 'label' | 'category'> & Partial<Pick<Card, 'icon' | 'kind' | 'description' | 'image'>> & { sceneIds?: string[] }
export type CustomSceneInput = Pick<CustomScene, 'name' | 'description'> & Partial<Pick<CustomScene, 'activities' | 'objects' | 'recommendedCardIds'>>
