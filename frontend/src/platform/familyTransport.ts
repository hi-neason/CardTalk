/** Native bearer credentials remain in memory; cold start requires login. Never store them in uni storage. */
let nativeToken='',epoch=0;
export function invalidateNativeFamilyAuthentication(){return ++epoch;}
export function beginNativeFamilyAuthentication(){nativeToken='';return ++epoch;}
export function setNativeFamilyToken(token:string,expectedEpoch=epoch){if(expectedEpoch!==epoch)return false;if(!/^[A-Za-z0-9_-]{43}$/.test(token))throw new Error('家庭登录凭证格式不正确');nativeToken=token;return true;}
export function clearNativeFamilyToken(){epoch++;nativeToken='';}
export function familyHeaders():Record<string,string>{
  // #ifdef APP
  return nativeToken?{Authorization:`Bearer ${nativeToken}`} : {};
  // #endif
  // #ifndef APP
  return {};
  // #endif
}
