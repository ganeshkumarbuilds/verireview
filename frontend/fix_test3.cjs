const fs = require('fs');
const f = 'C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx';
const c = fs.readFileSync(f, 'utf8');
// Fix the ending - replace the extra closing braces
const n = c.replace(/\}\);\s*\}\s*\}\s*\{\s*\}\s*\}\s*\}\s*$/, '})');
fs.writeFileSync(f, n);
console.log('Fixed');