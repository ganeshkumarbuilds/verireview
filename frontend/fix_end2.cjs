const fs = require('fs');
const f = 'C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx';
const c = fs.readFileSync(f, 'utf8');

// Add the missing closing braces
const n = c + '\n});\n});\n';
fs.writeFileSync(f, n);
console.log('Fixed');