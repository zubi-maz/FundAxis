/* Test-only browser checks for real Spring-rendered H2 snapshots.
 * Run from the repository root after the reporting integration tests.
 * Uses Node built-ins, installed Chrome, and a loopback-only fixture server.
 * This does not log in to or modify the production FundAxis application.
 */
const fs=require('node:fs'), path=require('node:path'), http=require('node:http');
const {spawn}=require('node:child_process');
const mode=process.argv[2]||'after';
const root=path.resolve('target/ui-audit',mode);
const vendor=path.resolve('target/ui-audit/vendor');fs.mkdirSync(vendor,{recursive:true});
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function cache(url,name){const file=path.join(vendor,name);if(!fs.existsSync(file)){const r=await fetch(url);if(!r.ok)throw Error('Vendor asset unavailable: '+name);fs.writeFileSync(file,Buffer.from(await r.arrayBuffer()));}return file;}
class CDP{
 constructor(url){this.ws=new WebSocket(url);this.counter=0;this.pending=new Map();this.errors=[];this.ws.addEventListener('message',event=>{const m=JSON.parse(event.data);if(m.id){const p=this.pending.get(m.id);if(p){this.pending.delete(m.id);m.error?p.reject(Error(m.error.message)):p.resolve(m.result);}}else if(m.method==='Runtime.exceptionThrown')this.errors.push(m.params.exceptionDetails.text);});this.ready=new Promise((resolve,reject)=>{this.ws.addEventListener('open',resolve,{once:true});this.ws.addEventListener('error',reject,{once:true});});}
 async send(method,params={}){await this.ready;return new Promise((resolve,reject)=>{const id=++this.counter;this.pending.set(id,{resolve,reject});this.ws.send(JSON.stringify({id,method,params}));});}
 async evaluate(expression){if(expression.startsWith('const '))expression='(()=>{'+expression+'})()';const r=await this.send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text);return r.result.value;}
}
(async()=>{
 await cache('https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css','bootstrap.css');
 await cache('https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js','bootstrap.js');
 const icons=await cache('https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css','icons-original.css');
 let iconText=fs.readFileSync(icons,'utf8');
 for(const match of [...iconText.matchAll(/url\(["']?((?:\.\/)?fonts\/[^\)"']+)["']?\)/g)]){
  const name=match[1].split('/').pop().split('?')[0];await cache('https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/'+match[1],name);iconText=iconText.replace(match[1],'/vendor/'+name);
 }
 fs.writeFileSync(path.join(vendor,'icons.css'),iconText);
 let exportFailure=false;
 const serveFile=(response,file,type)=>{if(!fs.existsSync(file)){response.writeHead(404);return response.end('Not found');}response.writeHead(200,{'Content-Type':type});response.end(fs.readFileSync(file));};
 const server=http.createServer((request,response)=>{
  const url=new URL(request.url,'http://127.0.0.1:9338');let name=url.pathname;
  if(name.startsWith('/vendor/'))return serveFile(response,path.join(vendor,path.basename(name)),name.endsWith('.css')?'text/css':name.endsWith('.js')?'application/javascript':'font/woff2');
  if(name.startsWith('/css/')||name.startsWith('/js/')||name.startsWith('/images/')){
   const relative=name.slice(1);const file=mode==='before'?path.join(root,'static',relative):path.join(path.resolve('src/main/resources/static'),relative);
   return serveFile(response,file,name.endsWith('.css')?'text/css':name.endsWith('.js')?'application/javascript':'image/png');
  }
  if(name==='/favicon.ico')return serveFile(response,path.resolve('src/main/resources/static/images/favicon.ico'),'image/x-icon');
  if(name==='/admin/share-withdrawal-profit/employee') {
   if(url.searchParams.get('employeeId')!=='WD001'){response.writeHead(400,{'Content-Type':'application/json'});return response.end(JSON.stringify({message:'Employee not found.'}));}
   return serveFile(response,path.join(root,'employee.json'),'application/json');
  }
  if(name==='/admin/share-withdrawal-profit')name='/'+(url.searchParams.get('tab')||'overview')+'.html';
  if(name==='/admin/employees/lookup'){
   if(url.searchParams.get('employeeId')!=='EMP18'){response.writeHead(404);return response.end();}
   return serveFile(response,path.join(root,'lookup.json'),'application/json');
  }
  if(name.endsWith('/export')){
   if(exportFailure){response.writeHead(503,{'Content-Type':'text/plain'});return response.end('Unavailable');}
   const kind=name.includes('yearly-records')?'annual':'statement';
   response.setHeader('Content-Disposition','attachment; filename="FundAxis_'+kind+'_2026.xlsx"');
   return serveFile(response,path.join(root,kind+'.xlsx'),'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
  }
  if(name.endsWith('/yearly-records/details'))name='/detail.html';
  if(name==='/admin/contribution-workspace' && url.searchParams.get('tab')==='member-operations') name=url.searchParams.get('employeeName')==='Scale Test'?'/paged.html':url.searchParams.get('employeeId')?'/member.html':'/monthly.html';
  if(name==='/admin/contribution-workspace')name=url.searchParams.get('tab')==='yearly-records'?(url.searchParams.get('employeeId')==='EMP19'?'/annual-filtered.html':'/annual.html'):!url.searchParams.get('employeeId')?'/statement-empty.html':url.searchParams.get('statementType')==='CUSTOM'?'/statement-custom.html':'/statement.html';
  const file=path.join(root,path.basename(name));
  if(!fs.existsSync(file)){response.writeHead(404);return response.end('Not found');}
  let html=fs.readFileSync(file,'utf8');
  html=html.replaceAll('https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css','/vendor/bootstrap.css').replaceAll('https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js','/vendor/bootstrap.js').replace(/https:\/\/cdn\.jsdelivr\.net\/npm\/bootstrap-icons@[^/]+\/font\/bootstrap-icons(?:\.min)?\.css/g,'/vendor/icons.css');
  response.writeHead(200,{'Content-Type':'text/html;charset=UTF-8'});response.end(html);
 });
 await new Promise(r=>server.listen(9338,'127.0.0.1',r));
 const chrome=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--disable-extensions','--remote-debugging-port=9337','--user-data-dir='+path.resolve('target/ui-audit/chrome-profile'), 'about:blank'],{windowsHide:true,stdio:'ignore'});
 let cdp;
 const results=[];
 function check(condition,message){if(!condition)throw Error(message);}
 async function until(fn,message){for(let i=0;i<100;i++){if(await fn())return;await pause(100);}throw Error(message);}
 try{
  let tabs;
  await until(async()=>{try{tabs=await (await fetch('http://127.0.0.1:9337/json/list')).json();return tabs.some(t=>t.type==='page');}catch{return false;}},'Chrome did not start');
  cdp=new CDP(tabs.find(t=>t.type==='page').webSocketDebuggerUrl);await cdp.ready;
  await cdp.send('Page.enable');await cdp.send('Runtime.enable');
  const downloadPath=path.join(root,'downloads');fs.mkdirSync(downloadPath,{recursive:true});
  await cdp.send('Browser.setDownloadBehavior',{behavior:'allow',downloadPath});
  async function navigate(name,width=1440){
   await cdp.send('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
   await cdp.send('Page.navigate',{url:'http://127.0.0.1:9338/'+name});
   await until(()=>cdp.evaluate("document.readyState==='complete' && !!document.querySelector('.cs-workspace')"),'Page did not finish loading: '+name);
   await cdp.evaluate('document.fonts.ready');await pause(150);
  }
  async function screenshot(name){const r=await cdp.send('Page.captureScreenshot',{captureBeyondViewport:!name.startsWith('drawer-')});fs.writeFileSync(path.join(root,name+'.png'),Buffer.from(r.data,'base64'));}
  if(mode==='member-operations') {
   for(const width of [1440,1280,768,390]) for(const page of ['member','monthly','holding','credit','filtered','empty','paged','last-page']) {
    await navigate(page+'.html',width);
    const metrics=await cdp.evaluate("({width:innerWidth,scroll:document.documentElement.scrollWidth,tabs:document.querySelectorAll('.cs-tabs .cs-tab').length,active:document.querySelectorAll('.cs-tabs .is-active').length})");
    check(metrics.scroll<=metrics.width+1,'Member page overflow '+page+' '+width+': '+JSON.stringify(metrics));
    check(metrics.tabs===5 && metrics.active===1,'Wrong unified navigation');
    const register=await cdp.evaluate("({sections:document.querySelectorAll('#mfo-monthly').length,headings:[...document.querySelectorAll('h3')].filter(h=>h.textContent.trim()==='Monthly Contributions & Payments').length,intros:[...document.querySelectorAll('.mfo-workspace .cs-form-help')].filter(p=>p.textContent.startsWith('Viewing records does not generate')).length,headers:document.querySelectorAll('#mfo-contribution-register > thead').length,members:document.querySelectorAll('.mfo-member').length})");
    check(register.sections===1&&register.headings===1&&register.intros===1,'Duplicate monthly rendering '+page+': '+JSON.stringify(register));
    check(register.headers===(['filtered','empty'].includes(page)?0:1),'Incorrect financial table header count '+page);
    check(register.members===(['member','holding','credit'].includes(page)?1:0),'Employee summary visibility incorrect '+page);
    const filters=await cdp.evaluate("(()=>{const boxes=[...document.querySelectorAll('#mfo-context fieldset>div')].map(e=>e.getBoundingClientRect());return boxes.every((a,i)=>boxes.every((b,j)=>i===j||a.right<=b.left+1||b.right<=a.left+1||a.bottom<=b.top+1||b.bottom<=a.top+1))})()");
    check(filters,'Overlapping filter fields '+page+' '+width);
    check(await cdp.evaluate("[...document.querySelectorAll('.mfo-action-menu')].every(m=>m.open===(innerWidth>=768))"),'Responsive action menu state incorrect');
    check(await cdp.evaluate("[...document.querySelectorAll('#mfo-contribution-register tbody tr')].every(row=>{const payment=new URL(row.querySelector('.mfo-payment-action').href);const member=new URL(row.querySelector('.mfo-member-link').href);const links=[...row.querySelectorAll('.mfo-secondary-links a')];const id=payment.searchParams.get('contributionId');return payment.searchParams.get('employeeId')===member.searchParams.get('employeeId')&&links[0].pathname.endsWith('/'+id)&&links[1].pathname.endsWith('/'+id+'/edit')})"),'Row actions point to the wrong employee/contribution');

    check(await cdp.evaluate("[...document.querySelectorAll('.mfo-workspace form[method=post]')].every(f=>f.querySelector('[name=_csrf]'))"),'Missing CSRF token');
    results.push({page,viewport:width,...metrics});await screenshot(page+'-'+width);
   }
   await navigate('paged.html',390);
   await cdp.evaluate("document.getElementById('mfo-name').focus()");
   check(await cdp.evaluate("document.getElementById('mfo-context').checkValidity() && !document.querySelector('#mfo-context button[type=submit]').disabled && document.activeElement.id==='mfo-name'"),'Context not ready for keyboard submission');
   await cdp.send('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,text:'\r',unmodifiedText:'\r'});
   await cdp.send('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});
   await until(()=>cdp.evaluate("location.pathname==='/admin/contribution-workspace' && document.readyState==='complete' && !!document.querySelector('.mfo-workspace')"),'Enter did not submit context');
   check(await cdp.evaluate("(()=>{const q=new URLSearchParams(location.search);return q.get('tab')==='member-operations'&&q.get('employeeName')==='Scale Test'&&q.get('year')==='2026'&&q.get('month')==='1'&&q.get('status')==='PENDING'&&q.get('size')==='10'&&q.get('contextApplied')==='1'})()"),'Keyboard submission loses context');
   check(await cdp.evaluate("!!document.getElementById('mfo-context-feedback') && document.querySelector('.mfo-page-summary').textContent.includes('205')"),'Applied context or large-dataset pagination feedback missing');
   check(await cdp.evaluate("(()=>{const u=new URL(document.querySelector('.mfo-pagination [rel=next]').href);return u.searchParams.get('employeeName')==='Scale Test'&&u.searchParams.get('status')==='PENDING'&&u.searchParams.get('size')==='10'&&u.searchParams.get('page')==='1'})()"),'Pagination drops selected filters');
   await cdp.evaluate("document.querySelector('.mfo-action-menu summary').click()");
   check(await cdp.evaluate("document.querySelector('.mfo-action-menu').open && document.querySelector('.mfo-secondary-links a').getClientRects().length>0"),'Mobile secondary actions inaccessible');
   for(const drawerWidth of [1440,390]) {
   await navigate('holding.html',drawerWidth);
   for(const name of ['allocate','adjust','rate','payment']) {
    await cdp.evaluate("document.querySelector('[data-bs-target=\"#mfo-"+name+"\"]').click()");
    await until(()=>cdp.evaluate("document.getElementById('mfo-"+name+"').classList.contains('show') && !document.getElementById('mfo-"+name+"').classList.contains('showing')"),'Drawer not opened: '+name);
    check(await cdp.evaluate("document.getElementById('mfo-"+name+"').contains(document.activeElement)"),'Drawer focus missing: '+name);
    await screenshot('drawer-'+name+'-'+drawerWidth);
    await cdp.send('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});
    await until(()=>cdp.evaluate("!document.getElementById('mfo-"+name+"').classList.contains('show')"),'Drawer failed Escape: '+name);await pause(450);
   }
   }
   // Payment and member-credit drawers: verify current rendered balances, record binding,
   // eligibility controls, and sticky actions without submitting financial writes.
   for(const drawerWidth of [1440,390]) {
    await navigate('credit.html',drawerWidth);
    const rowForms=await cdp.evaluate("({credits:[...document.querySelectorAll('.mfo-credit-ledger-table tbody tr,.mfo-allocation-table tbody tr')].some(r=>r.querySelector('form,input,select,textarea')),payments:[...document.querySelectorAll('.mfo-payment-history tbody tr')].some(r=>r.querySelector('form,input,select,textarea')),balance:document.getElementById('mfo-available-credit')?.textContent.trim()})");
    check(!rowForms.credits&&!rowForms.payments,'Inline financial forms remain in ledger rows');
    check(rowForms.balance==='BDT 85.00','Available credit summary mismatch: '+JSON.stringify(rowForms));
    check(await cdp.evaluate("document.querySelectorAll('.mfo-credit-recent-table tbody tr').length<=3"),'Compact recent credit list is unbounded');
    await cdp.evaluate("document.querySelector('[data-bs-target=\"#mfo-payment\"]').click()");
    await until(()=>cdp.evaluate("document.getElementById('mfo-payment').classList.contains('show')&&!document.getElementById('mfo-payment').classList.contains('showing')"),'Payment drawer did not open');
    const payment=await cdp.evaluate("(()=>{const p=document.getElementById('mfo-payment'),f=document.getElementById('mfo-payment-form'),b=document.querySelector('.mfo-drawer-footer'),o=document.getElementById('mfo-payment-outstanding'),d=document.getElementById('payDate');return {focused:p.contains(document.activeElement),employee:p.querySelector('.mfo-drawer-context').textContent,period:p.querySelector('.mfo-payment-period').textContent,due:document.getElementById('mfo-payment-due').textContent,cash:document.getElementById('mfo-payment-cash').textContent,credit:document.getElementById('mfo-payment-credit').textContent,outstanding:o.textContent,max:document.getElementById('payAmount').max,dateValue:d.value,dateMax:d.max,csrf:!!f.querySelector('[name=_csrf]'),footerVisible:b.getBoundingClientRect().bottom<=p.getBoundingClientRect().bottom+1&&b.getBoundingClientRect().top>=p.getBoundingClientRect().top}})()");
    check(payment.focused&&payment.employee.includes('EMP18')&&payment.period.includes('January 2026'),'Payment drawer lost selected employee or contribution period');
    check(payment.due==='BDT 100.00'&&payment.cash==='BDT 50.00'&&payment.credit==='BDT 20.00'&&payment.outstanding==='BDT 30.00'&&payment.max==='30.00','Payment drawer settlement summary is inconsistent: '+JSON.stringify(payment));
    check(payment.csrf&&payment.footerVisible&&payment.dateMax===payment.dateValue,'Payment drawer footer/date/CSRF check failed');
    await cdp.evaluate("document.getElementById('mfo-pay-full').click()");
    check(await cdp.evaluate("document.getElementById('payAmount').value==='30.00'&&!document.getElementById('mfo-payment-form').dataset.csSubmitting&&location.pathname==='/credit.html'"),'Pay Full Outstanding submitted or changed the amount incorrectly');
    check(await cdp.evaluate("(()=>{const a=document.getElementById('payAmount');a.value='31';return a.validity.rangeOverflow})()"),'Payment amount above current outstanding passes browser validation');
    check(await cdp.evaluate("(()=>{const f=document.getElementById('mfo-payment-form'),a=f.elements.namedItem('amount');a.value='10';window.confirm=()=>true;const first=new Event('submit',{bubbles:true,cancelable:true});f.dispatchEvent(first);const second=new Event('submit',{bubbles:true,cancelable:true});f.dispatchEvent(second);return f.dataset.csSubmitting==='true'&&second.defaultPrevented})()"),'Payment drawer does not guard repeated submission');
    await cdp.evaluate("bootstrap.Offcanvas.getOrCreateInstance(document.getElementById('mfo-payment')).hide()");await until(()=>cdp.evaluate("!document.getElementById('mfo-payment').classList.contains('show')"),'Payment drawer did not close');
    await cdp.evaluate("document.querySelector('[data-bs-target=\"#mfo-collection\"]').click()");
    await until(()=>cdp.evaluate("document.getElementById('mfo-collection').classList.contains('show')&&!document.getElementById('mfo-collection').classList.contains('showing')"),'Collection drawer did not open');
    check(await cdp.evaluate("(()=>{const p=document.getElementById('mfo-collection'),f=document.getElementById('mfo-collection-form');return p.querySelector('.mfo-drawer-context').textContent.includes('EMP18')&&f.querySelector('[name=employeeId]').value==='EMP18'&&!!f.querySelector('[name=_csrf]')&&p.contains(document.activeElement)})()"),'Collection drawer did not reuse verified employee context/CSRF');
    await cdp.evaluate("bootstrap.Offcanvas.getOrCreateInstance(document.getElementById('mfo-collection')).hide()");await until(()=>cdp.evaluate("!document.getElementById('mfo-collection').classList.contains('show')"),'Collection drawer did not close');
    await cdp.evaluate("document.querySelector('[data-mfo-open-details=\"#mfo-credit-ledger\"]').click()");
    check(await cdp.evaluate("document.getElementById('mfo-credit-ledger').open"),'Credit ledger does not open contextually');
    const reversedState=await cdp.evaluate("(()=>({reversedCredit:!![...document.querySelectorAll('.mfo-credit-ledger-table tbody tr')].find(r=>r.textContent.includes('REVERSED'))?.querySelector('[data-mfo-bound-open=reverse-credit]'),fullyAppliedApply:[...document.querySelectorAll('.mfo-credit-ledger-table tbody tr')].filter(r=>r.textContent.includes('FULLY APPLIED')).some(r=>r.querySelector('[data-mfo-bound-open=apply-credit]')),reversedPayments:[...document.querySelectorAll('#mfo-collections .mfo-history tbody tr')].filter(r=>r.textContent.includes('REVERSED')).every(r=>!r.querySelector('[data-mfo-bound-open=reverse-payment]')),reversalCopy:document.querySelector('#mfo-collections .mfo-history')?.textContent.includes('Excluded from net cash received')}))()");
    check(!reversedState.reversedCredit&&!reversedState.fullyAppliedApply&&reversedState.reversedPayments&&reversedState.reversalCopy,'Ineligible/reversed credit or payment actions are exposed: '+JSON.stringify(reversedState));
    const creditRows=await cdp.evaluate("[...document.querySelectorAll('.mfo-credit-ledger-table tbody tr')].length");
    check(creditRows>=1,'Credit ledger has no actual records');
    await cdp.evaluate("(()=>{const b=[...document.querySelectorAll('[data-mfo-bound-open=apply-credit]')].find(x=>x.dataset.reference==='Credit 7');let p=b.closest('details');while(p){p.open=true;p=p.parentElement.closest('details')}b.click()})()");
    await until(()=>cdp.evaluate("document.getElementById('mfo-apply-credit').classList.contains('show')&&!document.getElementById('mfo-apply-credit').classList.contains('showing')"),'Apply-credit drawer did not open');
    const bound=await cdp.evaluate("(()=>{const p=document.getElementById('mfo-apply-credit'),f=p.querySelector('[data-mfo-bound-form]'),a=f.elements.namedItem('amount'),d=f.elements.namedItem('appliedDate');return {action:f.action,reference:p.querySelector('[data-mfo-bound-reference]').textContent,limit:a.max,contribution:f.elements.namedItem('contributionId').value,employee:f.elements.namedItem('employeeId').value,minDate:d.min,enabled:!f.querySelector('[data-mfo-bound-fields]').disabled,submit:!f.querySelector('[data-mfo-bound-submit]').disabled,csrf:!!f.querySelector('[name=_csrf]')}})()");
    check(bound.action.endsWith('/credits/7/apply')&&bound.reference==='Credit 7'&&bound.limit==='5.00'&&bound.employee==='EMP18'&&bound.contribution&&bound.minDate==='2025-12-31'&&bound.enabled&&bound.submit&&bound.csrf,'Apply-credit drawer did not bind exact eligible record/current limit: '+JSON.stringify(bound));
    check(await cdp.evaluate("(()=>{const f=document.querySelector('#mfo-apply-credit form'),a=f.elements.namedItem('amount');a.value='6';return a.validity.rangeOverflow})()"),'Credit amount above available/outstanding limit passes browser validation');
    check(await cdp.evaluate("(()=>{const f=document.querySelector('#mfo-apply-credit form');f.elements.namedItem('amount').value='5';window.confirm=()=>true;const first=new Event('submit',{bubbles:true,cancelable:true});f.dispatchEvent(first);const second=new Event('submit',{bubbles:true,cancelable:true});f.dispatchEvent(second);return !first.defaultPrevented&&second.defaultPrevented&&f.dataset.csSubmitting==='true'})()"),'Bound credit action lacks a duplicate-submission guard or uses an invalid endpoint');
    await cdp.evaluate("bootstrap.Offcanvas.getOrCreateInstance(document.getElementById('mfo-apply-credit')).hide()");await until(()=>cdp.evaluate("!document.getElementById('mfo-apply-credit').classList.contains('show')"),'Apply-credit drawer did not close');
    await cdp.evaluate("(()=>{const b=[...document.querySelectorAll('[data-mfo-bound-open=reverse-allocation]')][0];let p=b.closest('details');while(p){p.open=true;p=p.parentElement.closest('details')}b.click()})()");
    await until(()=>cdp.evaluate("document.getElementById('mfo-reverse-allocation').classList.contains('show')&&!document.getElementById('mfo-reverse-allocation').classList.contains('showing')"),'Reverse-allocation drawer did not open');
    check(await cdp.evaluate("(()=>{const f=document.querySelector('#mfo-reverse-allocation form'),p=document.getElementById('mfo-reverse-allocation');return f.action.endsWith('/credits/allocations/12/reverse')&&p.querySelector('[data-mfo-bound-period]').textContent.includes('January 2026')&&f.elements.namedItem('reversalDate').min==='2026-01-20'&&f.elements.namedItem('reason').required&&f.dataset.csSubmitConfirm.includes('reversal')&&!!f.querySelector('[name=_csrf]')})()"),'Reversal drawer lost the allocation period, effective-date limit, reason or confirmation');
    await cdp.evaluate("bootstrap.Offcanvas.getOrCreateInstance(document.getElementById('mfo-reverse-allocation')).hide()");await until(()=>cdp.evaluate("!document.getElementById('mfo-reverse-allocation').classList.contains('show')"),'Reverse-allocation drawer did not close');
    await cdp.evaluate("(()=>{const b=[...document.querySelectorAll('[data-mfo-bound-open=reverse-credit]')][0];let p=b.closest('details');while(p){p.open=true;p=p.parentElement.closest('details')}b.click()})()");
    await until(()=>cdp.evaluate("document.getElementById('mfo-reverse-credit').classList.contains('show')&&!document.getElementById('mfo-reverse-credit').classList.contains('showing')"),'Reverse-collection drawer did not open');
    check(await cdp.evaluate("(()=>{const f=document.querySelector('#mfo-reverse-credit form'),p=document.getElementById('mfo-reverse-credit');return f.action.endsWith('/credits/8/reverse')&&p.querySelector('[data-mfo-bound-reference]').textContent==='Credit 8'&&p.querySelector('[data-mfo-bound-available]').textContent.includes('80.00')&&f.elements.namedItem('reason').required&&f.dataset.csSubmitConfirm.includes('reversal')})()"),'Reverse-collection drawer did not bind the eligible unallocated credit');
    check(cdp.errors.length===0,'Payment/credit browser exceptions: '+cdp.errors.join(', '));
    results.push({page:'payment-credit-actions',viewport:drawerWidth,...payment,creditRows});
    await screenshot('payment-credit-'+drawerWidth);
   }
   await navigate('holding.html',1440);
   await cdp.evaluate("const q=document.getElementById('adjustQty');q.value='-5';q.dispatchEvent(new Event('input',{bubbles:true}));");
   check(await cdp.evaluate("!document.getElementById('adjustQty').validity.valid"),'Negative resulting shares accepted');
   await cdp.evaluate("const q=document.getElementById('adjustQty');q.value='2';q.dispatchEvent(new Event('input',{bubbles:true}));");
   check(await cdp.evaluate("document.getElementById('mfo-result-quantity').textContent==='6'"),'Quantity preview incorrect');
   await cdp.evaluate("const e=document.getElementById('mfo-employee');e.value='EMP19';e.dispatchEvent(new Event('input',{bubbles:true}));");
   check(await cdp.evaluate("document.querySelector('.mfo-stale-notice') && [...document.querySelectorAll('.mfo-workspace form[method=post] button')].every(b=>b.disabled)"),'Stale context actions remain enabled');
   check(cdp.errors.length===0,'Browser exceptions: '+cdp.errors.join(', '));
   fs.writeFileSync(path.join(root,'browser-results.json'),JSON.stringify({mode,passed:true,viewports:results,jsExceptions:cdp.errors,environment:'Actual Spring/H2-rendered snapshots; no production financial operations'},null,2));
   console.log(JSON.stringify({mode,passed:true,pages:results.length,artifacts:root}));return;
  }
  if(mode==='withdrawal') {
   for(const width of [1440,1280,768,390]) for(const tab of ['overview','withdrawals','profit','history']) {
    await navigate(tab+'.html',width);
    const metrics=await cdp.evaluate("({width:innerWidth,scroll:document.documentElement.scrollWidth,active:document.querySelectorAll('.swp-tabs .is-active').length})");
    check(metrics.scroll<=metrics.width+1,'Withdrawal page overflow '+tab+' '+width+': '+JSON.stringify(metrics));
    check(metrics.active===1,'Missing active module tab');
    if(tab==='overview')check(await cdp.evaluate("document.querySelector('.swp-workspace').textContent.includes('Requires Your Attention')"),'Missing attention overview');
    if(tab==='profit')check(await cdp.evaluate("document.querySelectorAll('.swp-workspace form[method=post]').length===0"),'Profit preparation exposes financial posting');
    results.push({tab,viewport:width,...metrics});await screenshot(tab+'-'+width);
   }
   await navigate('withdrawals.html');
   await cdp.evaluate("const select=document.getElementById('swp-category');select.value='OLD';select.dispatchEvent(new Event('change',{bubbles:true}));const q=document.getElementById('swp-quantity');q.value='9';q.dispatchEvent(new Event('input',{bubbles:true}));");
   check(await cdp.evaluate("!document.getElementById('swp-quantity').validity.valid"),'Over-reserved quantity accepted by UI');
   await cdp.evaluate("const input=document.getElementById('swp-employee-id');input.value='WD001';input.dispatchEvent(new Event('input',{bubbles:true}));");
   check(await cdp.evaluate("document.getElementById('swp-submit').disabled && document.getElementById('swp-employee-card').hidden && document.getElementById('swp-request-details').hidden"),'Stale employee details remain actionable');
   await cdp.evaluate("document.querySelector('#swp-lookup-form button').click()");
   await until(()=>cdp.evaluate("!document.getElementById('swp-employee-card').hidden"),'Withdrawal employee lookup failed');
   check(await cdp.evaluate("document.querySelectorAll('#swp-category option').length===3 && document.getElementById('swp-selected-employee').value==='WD001'"),'Holding selection/business ID not populated');
   await cdp.evaluate("const s=document.getElementById('swp-category');s.value='OLD';s.dispatchEvent(new Event('change',{bubbles:true}));const q=document.getElementById('swp-quantity');q.value='2';q.dispatchEvent(new Event('input',{bubbles:true}));const f=document.getElementById('swp-request-form');f.elements.reason.value='Browser isolated review';f.querySelector('input[type=checkbox]').checked=true;f.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));");
   check(await cdp.evaluate("document.getElementById('swp-request-form').dataset.submitting==='true' && document.getElementById('swp-submit').disabled"),'Duplicate-submit guard missing');
   check(cdp.errors.length===0,'Browser script exceptions: '+cdp.errors.join(', '));
   fs.writeFileSync(path.join(root,'browser-results.json'),JSON.stringify({mode,passed:true,viewports:results,jsExceptions:cdp.errors,environment:'Actual Spring/H2-rendered snapshots; loopback fixture lookup; no production operations'},null,2));
   console.log(JSON.stringify({mode,passed:true,pages:results.length,artifacts:root}));return;
  }
  for(const width of [1440,1280,768,390]){
   for(const page of ['annual.html','statement-empty.html','statement.html']){
    await navigate(page,width);
    const metrics=await cdp.evaluate(`(()=>{const rows=[...document.querySelectorAll('.cs-yr-chart-month')];return {width:innerWidth,scroll:document.documentElement.scrollWidth,months:rows.length,unique:new Set(rows.map(r=>r.dataset.month||r.querySelector('b').textContent)).size,overlap:rows.some((r,i)=>i&&rows[i-1].getBoundingClientRect().bottom>r.getBoundingClientRect().top+1),steps:document.querySelectorAll('.cs-st-step').length}})()`);
    if(page==='annual.html'){check(metrics.months===12&&metrics.unique===12,'Duplicate or missing month');check(!metrics.overlap,'Overlapping month rows');}
    if(mode==='after'){
     check(metrics.scroll<=metrics.width+1,'Page overflow '+page+' at '+width+': '+JSON.stringify(metrics));
     if(page!=='annual.html')check(metrics.steps===3,'Missing guided workflow');
     if(page==='annual.html'){
      const bars=await cdp.evaluate(`(()=>{const scale=Number(document.querySelector('.cs-yr-chart-rows').dataset.scale);return [...document.querySelectorAll('.cs-yr-chart-series')].map(s=>{const track=s.querySelector('.cs-yr-track').getBoundingClientRect();const bar=s.querySelector('.cs-yr-bar').getBoundingClientRect();const ratio=Number(s.dataset.amount)/scale;return Math.abs(bar.width-track.width*ratio)<1.2})})()`);
      check(bars.length===24&&bars.every(Boolean),'Incorrect chart widths or inconsistent scales');
     }
    }
    results.push({page,viewport:width,...metrics});await screenshot(page.replace('.html','')+'-'+width);
   }
  }
  if(mode==='after'){
   await navigate('annual-filtered.html');
   check(await cdp.evaluate("[...document.querySelectorAll('.cs-yr-chart-series')].every(s=>Number(s.dataset.amount)===0)"),'Chart did not reflect filtered data');
   await navigate('annual.html');
   await cdp.evaluate("document.querySelector('.cs-yr-details').click()");
   await until(()=>cdp.evaluate("!!document.querySelector('#cs-yr-detail-body .cs-yr-detail-table')"),'Modal failed to load');
   check(await cdp.evaluate("document.querySelectorAll('#cs-yr-detail-body tbody tr').length===12"),'Modal does not contain twelve months');
   await screenshot('annual-details-desktop');
   await cdp.send('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});
   await pause(400);check(await cdp.evaluate("!document.querySelector('#cs-yr-modal').classList.contains('show')"),'Escape did not close modal');
   await cdp.evaluate("document.querySelector('[data-cs-report-export]').click()");
   await until(()=>cdp.evaluate("document.querySelector('[data-cs-report-feedback]').textContent.includes('is ready')"),'Excel success feedback missing');
   await until(async()=>fs.readdirSync(downloadPath).some(n=>n.endsWith('.xlsx')),'Excel download missing');
   exportFailure=true;
   await cdp.evaluate("document.querySelector('[data-cs-report-export]').click()");
   await until(()=>cdp.evaluate("document.querySelector('[data-cs-report-feedback]').classList.contains('is-error')"),'Export failure feedback missing');exportFailure=false;
   await navigate('statement-empty.html');
   check(await cdp.evaluate("!document.querySelector('[data-cs-report-export]')"),'Export visible without a statement');
   await cdp.evaluate("const e=document.getElementById('cs-st-employee-id');e.value='EMP18';e.dispatchEvent(new Event('input',{bubbles:true}));");
   await until(()=>cdp.evaluate("!document.getElementById('cs-st-verified').hidden"),'Verified employee card missing');
   check(await cdp.evaluate("document.getElementById('cs-st-employee-name').textContent==='Amina Rahman' && document.getElementById('cs-st-verified-joined').textContent==='2025-01-01'"),'Incorrect verified details');
   await screenshot('statement-verified-employee');
   await cdp.evaluate("const e=document.getElementById('cs-st-employee-id');e.value='UNKNOWN';e.dispatchEvent(new Event('input',{bubbles:true}));");
   await until(()=>cdp.evaluate("document.getElementById('cs-st-lookup-status').textContent.includes('not found')"),'Invalid employee feedback missing');
   check(await cdp.evaluate("document.getElementById('cs-st-verified').hidden"),'Stale employee remains visible');
   await cdp.evaluate("const t=document.getElementById('cs-st-type');t.value='CUSTOM';t.dispatchEvent(new Event('change',{bubbles:true}));const f=document.getElementById('cs-st-generator');f.elements.startDate.value='2026-01-02';f.elements.endDate.value='2026-02-28';f.dispatchEvent(new Event('submit',{cancelable:true,bubbles:true}));");
   check(await cdp.evaluate("document.getElementById('cs-st-year').disabled && !document.querySelector('[data-cs-st-custom]').hidden && !document.getElementById('cs-st-generator').elements.startDate.validity.valid && !document.getElementById('cs-st-generate').disabled"),'Custom range validation failed');
   await navigate('statement-custom.html',390);await screenshot('statement-custom-mobile');
   await navigate('statement.html');
   await cdp.evaluate("const e=document.getElementById('cs-st-employee-id');e.value='EMP19';e.dispatchEvent(new Event('input',{bubbles:true}));");
   check(await cdp.evaluate("document.getElementById('cs-st-print').disabled && document.querySelector('[data-cs-report-export]').getAttribute('aria-disabled')==='true' && !document.querySelector('.cs-st-draft-notice').hidden"),'Stale preview exports not blocked');
   await navigate('statement.html',1123);await cdp.send('Emulation.setEmulatedMedia',{media:'print'});
   check(await cdp.evaluate("document.querySelector('.fa-sidebar').getClientRects().length===0 && document.querySelector('.cs-st-generator-card').getClientRects().length===0 && document.getElementById('cs-st-print-body').getClientRects().length>0 && document.querySelector('.cs-tabs').getClientRects().length===0"),'Print scope exposes application shell');
   await screenshot('statement-print');
   const pdf=await cdp.send('Page.printToPDF',{printBackground:true,preferCSSPageSize:true});
   fs.writeFileSync(path.join(root,'statement-print.pdf'),Buffer.from(pdf.data,'base64'));
   check(fs.statSync(path.join(root,'statement-print.pdf')).size>1000,'Print PDF not generated');
   await cdp.send('Emulation.setEmulatedMedia',{media:''});
  }
  check(cdp.errors.length===0,'Browser JavaScript exceptions: '+cdp.errors.join(', '));
  fs.writeFileSync(path.join(root,'browser-results.json'),JSON.stringify({mode,passed:true,viewports:results,jsExceptions:cdp.errors,environment:'Isolated actual Spring-rendered H2 snapshots with local fixture HTTP endpoints'},null,2));
  console.log(JSON.stringify({mode,passed:true,pages:results.length,artifacts:root}));
 }finally{if(cdp){try{await Promise.race([cdp.send('Browser.close'),pause(1000)]);}catch{}cdp.ws.close();}chrome.kill();server.close();}
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
