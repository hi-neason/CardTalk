import { expect, it } from 'vitest';
import { createState, createFamilyProfile, createRoom, addCard, recordRawExpression, endRoom, saveCommunicationReport, removeRoom, CARDS, validateState } from '../src/domain';
import { deleteLocalProfileData } from '../src/domain/data';
it('deleting a profile removes its all-profile report even when source history was deleted earlier', () => {
  let state = createFamilyProfile(createState(), { name: '删除档案', preset: 'adult' }); const profile = state.family.profiles[0]!;
  state = createRoom(state, { profileId: profile.id, preset: 'adult', communicatorName: profile.name, sceneId: null, participants: [], source: 'unknown' });
  state = endRoom(recordRawExpression(addCard(state, CARDS[0]!))); state = saveCommunicationReport(state, { profileId: 'all' });
  state = removeRoom(state, state.rooms[0]!.id); expect(state.reports).toHaveLength(1);
  const next = deleteLocalProfileData(state, profile.id); expect(next.reports).toHaveLength(0); expect(next.family.profiles).toHaveLength(0); expect(validateState(next).valid).toBe(true);
});
