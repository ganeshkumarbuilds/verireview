const fs = require('fs');
const f = 'C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx';
const c = fs.readFileSync(f, 'utf8');
// The file should end with: }); for test, }); for describe, }); for outer
// Currently has: }); } });  }); 
// We need to replace from "});" after the test to end with just the right closing
const n = c.replace(/}\s*\}\s*\}\s*\{\s*\}\s*\}\s*\}\s*$/, '})');
fs.writeFileSync(f, n);
console.log('Fixed');