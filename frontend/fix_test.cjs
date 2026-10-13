const fs = require('fs');
const f = 'C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx';
const c = fs.readFileSync(f, 'utf8');
// Remove extra closing braces at the end
const n = c.replace(/\}\s*\}\s*\}\s*\}\s*\}\s*\}$/, '}\n})');
fs.writeFileSync(f, n);
console.log('Fixed');