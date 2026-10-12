export type Emotion = 'neutral' | 'happy' | 'sad' | 'angry' | 'fearful'
export interface SelectedEmotion { emotion: Emotion; conflict: boolean; selectedEmotions: Exclude<Emotion, 'neutral'>[]; suppressionReason?: 'negation' }
export type Preset = 'children' | 'adult'
export type Source = 'independent' | 'assisted' | 'unknown'
export interface Card { id: string; label: string; category: string; icon: string; kind: 'word' | 'phrase'; description?: string }
export interface Context { preset: Preset; sceneId: string | null; participants: { id: string; name: string }[]; communicatorName: string; source: Source }
export interface Candidate { id: string; text: string; sourceCardIds: string[] }
export interface Expansion { requestId: string; inputVersion: number; candidates: Candidate[]; needsClarification: boolean; clarification: string | null; promptVersion: string; remaining: number }
export type PlaybackStatus = 'started' | 'completed' | 'interrupted' | 'failed' | 'unavailable'
export type FeedbackValue = 'understood' | 'clarify' | 'not-understood' | 'unrecorded'
export interface Turn { id: string; createdAt: string; cardSnapshot: Card[]; contextSnapshot: Context; expansion?: Expansion; finalText: string; selectedCandidateId?: string; source: Source; wasEdited: boolean; kind: 'generated' | 'raw'; playbackEvents: { id: string; status: PlaybackStatus; at: string }[]; feedback: { value: FeedbackValue; by: string; at: string; note?: string } | null }
export interface Room { id: string; context: Context; startedAt: string; endedAt: string | null; status: 'active' | 'ended'; turns: Turn[]; draft: { cards: Card[]; version: number; expansion: Expansion | null; confirmedText: string | null; selectedCandidateId: string | null; confirmedTurnId: string | null } }
export interface State { version: 1; rooms: Room[]; activeRoomId: string | null; settings: { fontSize: 'normal' | 'large'; rate: number; preset: Preset; aiExpansionEnabled: boolean; speechSynthesisEnabled: boolean; emotionRecognitionEnabled: boolean } }
