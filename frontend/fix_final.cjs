const fs = require('fs');
const f = 'C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx';
const c = fs.readFileSync(f, 'utf8');

// Find the last "});" that should be the end of the file
// We want to keep everything up to and including the last proper "});" 
// The file should end with: }); for test, }); for describe

// Find the position of the last valid "});" that closes the outer describe
// Let's find the last "});" that is followed only by whitespace and invalid closing braces
const lastValidEnd = c.lastIndexOf('});\n})');

// If found, truncate there and add proper ending
if (lastValidEnd !== -1) {
  const n = c.substring(0, lastValidEnd + 4) + '\n';
  fs.writeFileSync(f, n);
  console.log('Fixed - truncated to last valid end');
} else {
  console.log('Could not find valid end');
}