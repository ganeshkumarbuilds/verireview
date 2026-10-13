const fs = require('fs');
const f = 'C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx';
const c = fs.readFileSync(f, 'utf8');

// Find the last valid "});" that should be the end of the file
// The file should end with: }); for test, }); for describe = 2 closing
// Currently has: }); }); }); = 3 closing
// Find the position of the second-to-last "});"
const lines = c.split('\n');
let closingCount = 0;
let endIndex = -1;

for (let i = lines.length - 1; i >= 0; i--) {
  if (lines[i].trim() === '});') {
    closingCount++;
    if (closingCount === 2) {
      endIndex = i;
      break;
    }
  }
}

if (endIndex !== -1) {
  // Keep everything up to and including this line
  const n = lines.slice(0, endIndex + 1).join('\n') + '\n';
  fs.writeFileSync(f, n);
  console.log('Fixed - kept', endIndex + 1, 'lines');
} else {
  console.log('Could not find second closing brace');
}