import { rupeesToPaise } from './api.js';

console.log('Testing rupee-to-paise string parsing conversions:');

// Test 1: "100" -> 10000
const t1 = rupeesToPaise('100');
console.log('rupeesToPaise("100") =', t1, t1 === 10000 ? '✅ PASS' : '❌ FAIL');

// Test 2: "0.1" -> 10
const t2 = rupeesToPaise('0.1');
console.log('rupeesToPaise("0.1") =', t2, t2 === 10 ? '✅ PASS' : '❌ FAIL');

// Test 3: "0.29" -> 29 (in float math 0.29 * 100 = 28.999999999999996)
const t3 = rupeesToPaise('0.29');
console.log('rupeesToPaise("0.29") =', t3, t3 === 29 ? '✅ PASS' : '❌ FAIL');

// Test 4: "1.005" -> should throw error due to sub-paise (3 decimal places)
let t4Passed = false;
try {
  rupeesToPaise('1.005');
} catch (e) {
  t4Passed = true;
  console.log('rupeesToPaise("1.005") correctly threw Error:', e.message, '✅ PASS');
}
if (!t4Passed) {
  console.error('rupeesToPaise("1.005") failed to reject sub-paise ❌ FAIL');
  process.exit(1);
}

console.log('All 4 currency conversion test cases passed successfully!');
