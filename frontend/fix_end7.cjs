const fs = require('fs');
const f = 'C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx';
const c = fs.readFileSync(f, 'utf8');

// The file has an extra `});` at the end. Remove one.
const n = c.replace(/\}\);\s*\}\);\s*\);\s*$/, '});\n});\n');
fs.writeFileSync(f, n);
console.log('Fixed');