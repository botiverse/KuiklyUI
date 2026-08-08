import localUnitTest from './LocalUnit.test';
import avatarCacheContractTest from './KRAvatarCacheContract.test';

export default function testsuite() {
  localUnitTest();
  avatarCacheContractTest();
}
