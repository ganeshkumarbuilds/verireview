const fs = require('fs');
const f = 'C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx';
const c = fs.readFileSync(f, 'utf8');

// The file should end with exactly two `});` for the two describe blocks
// Currently has three - remove the extra one
const n = c.replace(/\}\);\s*\}\);\s*\);\s*$/, '});\n});\n');
fs.writeFileSync(f, n);
console.log('Fixed');