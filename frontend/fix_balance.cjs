const fs = require('fs');
const f = 'C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx';
const c = fs.readFileSync(f, 'utf8');

// Count opening and closing braces for the test structure
// We need to find where the structure becomes unbalanced
const lines = c.split('\n');
let braceCount = 0;
let lastGoodLine = -1;

for (let i = 0; i < lines.length; i++) {
  const line = lines[i];
  const openBraces = (line.match(/\(\{/g) || []).length;
  const closeBraces = (line.match(/}\);/g) || []).length;
  // Also count standalone }); as closing
  const standaloneClose = (line.trim() === '});') ? 1 : 0;
  
  braceCount += openBraces + standaloneClose - closeBraces;
  
  if (braceCount >= 0) {
    lastGoodLine = i;
  } else {
    console.log('Unbalanced at line', i + 1, ':', lines[i].trim());
    break;
  }
}

console.log('Last good line:', lastGoodLine + 1);
console.log('Total lines:', lines.length);

// Keep only up to the last good line
if (lastGoodLine >= 0) {
  const n = lines.slice(0, lastGoodLine + 1).join('\n') + '\n';
  fs.writeFileSync(f, n);
  console.log('Fixed - kept', lastGoodLine + 1, 'lines');
}