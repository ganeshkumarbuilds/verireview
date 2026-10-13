const fs = require('fs');
const f = 'C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx';
const c = fs.readFileSync(f, 'utf8');

// Remove the stray `});` at the end and keep just the proper closing
// The file should end with: }); }); for the two describe blocks
// Currently has: }); }); }); - need to remove one
const n = c.replace(/\}\);\s*\}\);\s*\);\s*$/, '});\n});\n');
fs.writeFileSync(f, n);
console.log('Fixed');