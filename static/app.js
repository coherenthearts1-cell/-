'use strict';
const $=id=>document.getElementById(id);
const token=document.querySelector('meta[name="app-token"]').content;
let files=[],page=0,selected=new Set(),busy=false,wasRunning=false,deletePaths=[];
const pageSize=60;
async function api(route,data={}){
 const response=await fetch('/api/'+route,{method:'POST',headers:{'Content-Type':'application/json','X-App-Token':token},body:JSON.stringify(data)});
 const result=await response.json();if(!response.ok)throw Error(result.error||'عملیات انجام نشد');return result;
}
function report(error){$('error').textContent=error.message||String(error);}
function run(fn){return async()=>{$('error').textContent='';try{await fn();}catch(e){report(e);}};}
function state(row){return row.deleted_at?'deleted':row.present===0?'missing':row.verified_at?'verified':'new';}
const labels={deleted:'از گوشی حذف شده توسط برنامه',missing:'در آخرین پویش یافت نشده',verified:'کپی قبلاً تأیید شده',new:'هنوز کپی نشده'};
function filtered(){const q=$('search').value.toLocaleLowerCase(),filter=$('filter').value;return files.filter(r=>(filter==='all'||state(r)===filter)&&r.remote.toLocaleLowerCase().includes(q));}
function selection(){ $('selected').textContent=selected.size+' فایل انتخاب شده'; }
function render(){
 const list=filtered();page=Math.max(0,Math.min(page,Math.ceil(list.length/pageSize)-1));$('rows').replaceChildren();
 for(const row of list.slice(page*pageSize,(page+1)*pageSize)){
  const tr=document.createElement('tr'),cell=document.createElement('td'),box=document.createElement('input');box.type='checkbox';box.checked=selected.has(row.remote);box.disabled=busy||state(row)==='deleted'||state(row)==='missing';box.setAttribute('aria-label','انتخاب '+row.remote);box.onchange=()=>{if(box.checked)selected.add(row.remote);else selected.delete(row.remote);selection();};cell.append(box);tr.append(cell);
  for(const [value,kind] of [[row.remote,'path'],[labels[state(row)],''],[row.size===null?'ثبت نشده':(row.size/1048576).toFixed(2)+' مگابایت',''],[row.local||'نسخه ثبت‌شده ندارد','path']]){const td=document.createElement('td');td.textContent=value;td.className=kind;tr.append(td);}
  $('rows').append(tr);
 }
 $('page').textContent=` صفحه ${list.length?page+1:0} از ${Math.ceil(list.length/pageSize)}؛ ${list.length} فایل در این فیلتر `;
 $('prev').disabled=page===0;$('next').disabled=(page+1)*pageSize>=list.length;selection();
}
async function loadFiles(){const result=await api('files',{folder:$('folder').value});files=result.files;const valid=new Set(files.filter(r=>!['deleted','missing'].includes(state(r))).map(r=>r.remote));selected=new Set([...selected].filter(p=>valid.has(p)));const counts={new:0,verified:0,deleted:0,missing:0};const folders=new Map();for(const row of files){counts[state(row)]++;const path=row.remote.slice(0,row.remote.lastIndexOf('/'));const c=folders.get(path)||{total:0,verified:0};c.total++;if(row.verified_at)c.verified++;folders.set(path,c);}$('summary').textContent=`${files.length} فایل؛ ${counts.new} کپی‌نشده؛ ${counts.verified} کپی تأییدشده؛ ${counts.deleted} حذف‌شده؛ ${counts.missing} یافت‌نشده.`;$('folders').replaceChildren();for(const [path,c]of folders){const li=document.createElement('li');li.textContent=`${path}؛ کل ${c.total}؛ کپی تأییدشده ${c.verified}`;$('folders').append(li);}render();}
function setBusy(value){busy=value;for(const id of ['tools','connect','scan','refresh','copy','recognize','prepare-delete','export','pick','open','destination','folder','select-page','diagnose','get-models','analyze','get-apps','uninstall','install','push','pick-apk','pick-push','storage-settings','app-settings'])$(id).disabled=value;render();}
let timer;
async function poll(){try{const s=await api('status');$('status').textContent=s.message+(s.total?`؛ ${s.done} از ${s.total}`:'');$('device').textContent=s.connected?'گوشی متصل: '+s.model:'اتصال هنوز بررسی نشده است.';if(!$('destination').value)$('destination').value=localStorage.getItem('archive-destination')||s.default_destination;$('tools').hidden=s.tools;$('errors').replaceChildren();for(const error of s.errors){const li=document.createElement('li');li.textContent=error;$('errors').append(li);}$('error-details').querySelector('summary').textContent=`خطاهای عملیات: ${s.error_count}؛ حداکثر ۱۰۰ مورد نمایش داده می‌شود`;
 const finished=wasRunning&&!s.running;wasRunning=s.running;if(busy!==s.running)setBusy(s.running);if(finished){if(s.connected)await loadFiles();await loadWorkspace();}
 }catch(e){report(Error('ارتباط با برنامه محلی قطع شد. پنجره اجرای برنامه را بررسی کن.'));}finally{timer=setTimeout(poll,1800);}}
async function start(route,data){await api(route,data);wasRunning=true;setBusy(true);$('status').textContent='عملیات شروع شد؛ نتیجه در بخش وضعیت اعلام می‌شود.';}
$('tools').onclick=run(()=>start('tools'));
$('connect').onclick=run(async()=>{await api('connect');await loadFiles();$('status').textContent='گوشی شناسایی شد. حالا پوشه را پویش کن.';});
$('scan').onclick=run(()=>start('scan',{folder:$('folder').value}));
$('refresh').onclick=run(loadFiles);
$('pick').onclick=run(async()=>{const r=await api('pick-destination');if(r.path){$('destination').value=r.path;localStorage.setItem('archive-destination',r.path);}});
$('open').onclick=run(()=>api('open-destination',{destination:$('destination').value}));
$('destination').onchange=()=>localStorage.setItem('archive-destination',$('destination').value);
for(const button of document.querySelectorAll('[data-folder]'))button.onclick=()=>{if(busy)return;$('folder').value=button.dataset.folder;$('folder').focus();};
$('search').oninput=()=>{page=0;render();};$('filter').onchange=()=>{page=0;render();};
$('prev').onclick=()=>{page--;render();$('files-title').focus();};$('next').onclick=()=>{page++;render();$('files-title').focus();};
$('select-page').onclick=()=>{for(const r of filtered().slice(page*pageSize,(page+1)*pageSize))if(!['deleted','missing'].includes(state(r)))selected.add(r.remote);render();};
$('clear').onclick=()=>{selected.clear();render();};
$('copy').onclick=run(async()=>{if(!selected.size)throw Error('ابتدا فایل‌ها را انتخاب کن.');await start('copy',{paths:[...selected],destination:$('destination').value});});
$('recognize').onclick=run(async()=>{if(!selected.size)throw Error('ابتدا فایل‌های گوشی را انتخاب کن.');await start('recognize',{paths:[...selected],destination:$('destination').value});});
$('prepare-delete').onclick=run(()=>{deletePaths=files.filter(r=>selected.has(r.remote)&&state(r)==='verified').map(r=>r.remote);if(!deletePaths.length)throw Error('از میان انتخاب‌ها، فایلی با کپی تأییدشده وجود ندارد. ابتدا کپی کن.');$('delete-count').textContent=`${deletePaths.length} فایل برای بررسی و حذف انتخاب شده؛ موارد بدون کپی تأییدشده کنار گذاشته شده‌اند.`;$('confirm').value='';$('delete-dialog').showModal();});
$('cancel-delete').onclick=()=>{$('delete-dialog').close();$('prepare-delete').focus();};
$('delete').onclick=run(async()=>{if($('confirm').value!=='DELETE'){$('confirm').setCustomValidity('عبارت DELETE را دقیق وارد کن.');$('confirm').reportValidity();return;}await start('delete',{paths:deletePaths,confirmation:'DELETE'});$('delete-dialog').close();$('prepare-delete').focus();});
$('confirm').oninput=()=>$('confirm').setCustomValidity('');
$('export').onclick=run(async()=>{const r=await api('export');$('export-result').textContent='سابقه در این مسیر ذخیره شد: '+r.path;});

let workspaceIds={report:null,analysis:null,models:null},defaultPrompt='',apps=[],manageAction=null;
async function loadWorkspace(){const w=await api('workspace');defaultPrompt=w.default_prompt;if(!$('ai-prompt').value)$('ai-prompt').value=defaultPrompt;
 if(workspaceIds.report!==w.report_id){workspaceIds.report=w.report_id;$('report-text').value=w.report;$('report-path').textContent=w.report_path?'گزارش اصلی ذخیره‌شده: '+w.report_path:'';$('ai-consent').checked=false;}
 if(workspaceIds.analysis!==w.analysis_id){workspaceIds.analysis=w.analysis_id;$('analysis-text').value=w.analysis;$('analysis-note').textContent=w.analysis_path?'پاسخ ذخیره‌شده: '+w.analysis_path:'هنوز پاسخ تازه‌ای دریافت نشده است.';}
 if(workspaceIds.models!==w.models_id){workspaceIds.models=w.models_id;$('gemini-models').replaceChildren();$('model-choice').replaceChildren();const placeholder=document.createElement('option');placeholder.value='';placeholder.textContent='مدل را انتخاب کن';$('model-choice').append(placeholder);for(const model of w.models){const o=document.createElement('option');o.value=model.name;o.textContent=model.label+' — '+model.name;$('model-choice').append(o);const d=document.createElement('option');d.value=model.name;d.label=model.label;$('gemini-models').append(d);}}
}
function downloadText(text,name){if(!text.trim())throw Error('متنی برای دانلود وجود ندارد.');const blob=new Blob(['\ufeff',text],{type:'text/plain;charset=utf-8'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),10000);}
$('diagnose').onclick=run(()=>start('diagnose'));
$('get-models').onclick=run(()=>start('models',{key:$('gemini-key').value}));
$('forget-key').onclick=()=>{$('gemini-key').value='';$('status').textContent='کلید از کادر پاک شد؛ درخواست در حال اجرا لغو نمی‌شود.';};
$('model-choice').onchange=()=>{if($('model-choice').value)$('gemini-model').value=$('model-choice').value;};
$('reset-prompt').onclick=()=>{$('ai-prompt').value=defaultPrompt;$('ai-consent').checked=false;};
for(const id of ['report-text','symptoms','ai-prompt'])$(id).addEventListener('input',()=>{$('ai-consent').checked=false;});
$('import-report').onchange=run(async()=>{const file=$('import-report').files[0];if(!file)return;if(file.size>1500000)throw Error('گزارش انتخابی بیش از حد بزرگ است.');const text=await file.text();if(text.length>350000)throw Error('گزارش باید حداکثر ۳۵۰ هزار نویسه باشد.');$('report-text').value=text;$('report-path').textContent='گزارش واردشده از فایل: '+file.name;$('ai-consent').checked=false;});
$('download-report').onclick=run(()=>downloadText($('report-text').value,'Phone-Report.txt'));
$('analyze').onclick=run(async()=>{if(!$('ai-consent').checked)throw Error('ابتدا گزارش را بررسی و رضایت ارسال به گوگل را تأیید کن.');if(!$('report-text').value.trim()||!$('ai-prompt').value.trim()||!$('gemini-model').value.trim())throw Error('گزارش، پرامپت و نام مدل را کامل کن.');await start('analyze',{key:$('gemini-key').value,model:$('gemini-model').value.trim(),report:$('report-text').value,prompt:$('ai-prompt').value,symptoms:$('symptoms').value,consent:true});});
$('read-analysis').onclick=()=>{$('analysis-text').focus();};
$('copy-analysis').onclick=run(async()=>{if(!$('analysis-text').value)throw Error('ابتدا تحلیل بگیر.');try{await navigator.clipboard.writeText($('analysis-text').value);$('status').textContent='پاسخ کپی شد.';}catch{$('analysis-text').focus();$('analysis-text').select();$('status').textContent='برای کپی پاسخ انتخاب‌شده، کنترل سی بزن.';}});
$('download-analysis').onclick=run(()=>downloadText($('analysis-text').value,'Gemini-Analysis.txt'));
function renderApps(){const q=$('app-search').value.toLowerCase();$('app-choice').replaceChildren();for(const app of apps.filter(x=>x.toLowerCase().includes(q))){const o=document.createElement('option');o.value=app;o.textContent=app;$('app-choice').append(o);}}
$('get-apps').onclick=run(async()=>{const r=await api('apps');apps=r.apps;renderApps();$('status').textContent=apps.length+' برنامه کاربر دریافت شد.';});$('app-search').oninput=renderApps;
async function pickFile(id){const r=await api('pick-destination',{kind:'file'});if(r.path)$(id).value=r.path;}
$('pick-apk').onclick=run(()=>pickFile('apk-path'));$('pick-push').onclick=run(()=>pickFile('push-local'));
function confirmManage(description,expected,route,data){manageAction={expected,route,data};$('manage-description').textContent=description+' برای تأیید بنویس: '+expected;$('manage-confirm').value='';$('manage-dialog').showModal();}
$('uninstall').onclick=run(()=>{const value=$('app-choice').value;if(!value)throw Error('ابتدا برنامه را انتخاب کن.');confirmManage('برنامه '+value+' و داده داخلی آن از گوشی حذف می‌شوند. نسخه پشتیبان ساخته نمی‌شود.','UNINSTALL '+value,'manage-app',{action:'uninstall',value});});
$('install').onclick=run(()=>{const value=$('apk-path').value.trim();if(!value)throw Error('ابتدا فایل APK را انتخاب کن.');confirmManage('فایل '+value+' روی گوشی نصب می‌شود. فقط فایل مورد اعتماد را نصب کن. برنامه موجود جایگزین نخواهد شد.','INSTALL','manage-app',{action:'install',value});});
$('push').onclick=run(()=>{const local=$('push-local').value.trim(),remote=$('push-remote').value.trim();if(!local||!remote)throw Error('مسیر مبدأ و مقصد را وارد کن.');confirmManage('فایل '+local+' به '+remote+' روی گوشی فرستاده می‌شود. فایل هم‌نام ممکن است جایگزین شود.','PUSH','push',{local,remote});});
$('manage-cancel').onclick=()=>$('manage-dialog').close();$('manage-confirm').oninput=()=>$('manage-confirm').setCustomValidity('');
$('manage-submit').onclick=run(async()=>{if(!manageAction)return;if($('manage-confirm').value!==manageAction.expected){$('manage-confirm').setCustomValidity('عبارت تأیید را دقیق وارد کن.');$('manage-confirm').reportValidity();return;}await start(manageAction.route,{...manageAction.data,confirmation:manageAction.expected});$('manage-dialog').close();$('status').focus();});
$('storage-settings').onclick=run(()=>api('phone-settings',{kind:'storage'}));$('app-settings').onclick=run(()=>api('phone-settings',{kind:'apps'}));

loadWorkspace().catch(report);
poll();
