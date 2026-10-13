const fs = require('fs');
const f = 'C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx';
const c = fs.readFileSync(f, 'utf8');

// The file should end with the proper closing for the describe block
// Currently ends with }); }); - need to add the final closing for the outer describe
const n = c + '\n});\n';
fs.writeFileSync(f, n);
console.log('Fixed');