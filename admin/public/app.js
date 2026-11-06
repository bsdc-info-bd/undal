import { BranchMap } from './branch-map.js';

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
const money = (paisa) => `৳${(Number(paisa || 0) / 100).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;
const km = (value) => `${Number(value || 0).toFixed(1)} km`;
const shortDate = (value) => {
  const time = typeof value === 'number' ? value : Date.parse(value);
  if (!Number.isFinite(time)) return '—';
  return new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: true, timeZone: 'Asia/Dhaka' }).format(new Date(time));
};
const relative = (value) => {
  const time = typeof value === 'number' ? value : Date.parse(value);
  if (!Number.isFinite(time)) return 'just now';
  const minutes = Math.max(0, Math.round((Date.now() - time) / 60000));
  return minutes < 1 ? 'just now' : minutes < 60 ? `${minutes}m ago` : `${Math.floor(minutes / 60)}h ago`;
};
const title = (value) => String(value || '').replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
const statusPill = (value) => `<span class="status status--${esc(value || 'closed')}">${esc(title(value || 'closed'))}</span>`;
const empty = (icon, heading, copy, action = '') => `<div class="empty-state"><span>${icon}</span><h3>${esc(heading)}</h3><p>${esc(copy)}</p>${action}</div>`;

const state = {
  token: sessionStorage.getItem('undal_admin_token') || '',
  uid: sessionStorage.getItem('undal_admin_uid') || '',
  data: null,
  route: location.hash.slice(1) || 'dashboard',
  branchFilter: '*',
  orderFilter: 'active',
  search: '',
  loading: false,
  timer: null,
  map: null,
};
const routes = {
  dashboard: ['OPERATIONS', 'Good day, Admin.'],
  branches: ['NETWORK', 'Your branches.'],
  menu: ['CATALOGUE', 'Menu library.'],
  orders: ['LIVE SERVICE', 'Orders in motion.'],
  requests: ['DELIVERY COVERAGE', 'Area requests.'],
  team: ['ACCESS & ROLES', 'Branch team.'],
};

async function api(path, options = {}) {
  const headers = { accept: 'application/json', ...(options.body ? { 'content-type': 'application/json' } : {}), ...(options.headers || {}) };
  if (state.token && !path.endsWith('/login')) headers.authorization = `Bearer ${state.token}`;
  const response = await fetch(path, { ...options, headers });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/admin/login') signOut(false);
    const error = new Error(payload.message || payload.hint || `The server answered HTTP ${response.status}.`);
    error.code = payload.error;
    error.status = response.status;
    throw error;
  }
  return payload;
}

function toast(message, error = false) {
  const node = document.createElement('div');
  node.className = `toast${error ? ' toast--error' : ''}`;
  node.innerHTML = `<span>${error ? '!' : '✓'}</span><div>${esc(message)}</div>`;
  $('#toast-region').append(node);
  setTimeout(() => node.remove(), error ? 7000 : 4200);
}

function setBusy(button, busy, label = 'Saving…') {
  if (!button) return;
  if (busy) { button.dataset.label = button.innerHTML; button.innerHTML = esc(label); button.disabled = true; }
  else { button.innerHTML = button.dataset.label || button.innerHTML; button.disabled = false; }
}

function signOut(notify = true) {
  state.token = '';
  state.uid = '';
  state.data = null;
  sessionStorage.removeItem('undal_admin_token');
  sessionStorage.removeItem('undal_admin_uid');
  clearInterval(state.timer);
  $('#admin-view').hidden = true;
  $('#login-view').hidden = false;
  if (notify) toast('Signed out of the control room.');
}

async function enter() {
  $('#login-view').hidden = true;
  $('#admin-view').hidden = false;
  await refresh(false);
  clearInterval(state.timer);
  state.timer = setInterval(() => { if (!document.hidden && !$('#editor-dialog').open) refresh(false, true); }, 20000);
}

async function refresh(showToast = false, background = false) {
  if (state.loading) return;
  state.loading = true;
  if (!background) $('#sync-label').textContent = 'Syncing…';
  try {
    state.data = await api('/api/admin/overview');
    $('#sync-label').textContent = `Synced ${new Intl.DateTimeFormat('en', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Dhaka' }).format(new Date())}`;
    $('#global-error').hidden = true;
    render();
    if (showToast) toast('Live data refreshed.');
  } catch (error) {
    if (error.status !== 401) {
      $('#global-error').textContent = `${error.message} Your last good data stays on screen.`;
      $('#global-error').hidden = false;
      $('#sync-label').textContent = 'Connection issue';
      if (!state.data) $('#content').innerHTML = empty('!', 'We could not open the control room', error.message, '<button class="button button--primary" data-action="retry">Try again</button>');
    }
  } finally {
    state.loading = false;
  }
}

function navigate(route) {
  if (!routes[route]) route = 'dashboard';
  state.route = route;
  if (location.hash !== `#${route}`) history.pushState(null, '', `#${route}`);
  render();
}

function counts() {
  const data = state.data || { branches: [], orders: [], deliveryRequests: [] };
  const activeOrders = data.orders.filter((order) => ['placed', 'accepted', 'ready', 'in_delivery'].includes(order.status));
  return {
    branches: data.branches.filter((branch) => branch.active !== false),
    activeOrders,
    pendingRequests: data.deliveryRequests.filter((request) => request.status === 'pending'),
  };
}

function render() {
  if (!state.data) return;
  const { branches, activeOrders, pendingRequests } = counts();
  $('#page-kicker').textContent = routes[state.route][0];
  $('#page-title').textContent = routes[state.route][1];
  $('#branch-nav-count').textContent = branches.length;
  $('#order-nav-count').textContent = activeOrders.length;
  $('#order-nav-count').hidden = activeOrders.length === 0;
  $('#request-nav-count').textContent = pendingRequests.length;
  $('#request-nav-count').hidden = pendingRequests.length === 0;
  $$('[data-route]').forEach((button) => button.classList.toggle('active', button.dataset.route === state.route));
  const renderers = { dashboard: renderDashboard, branches: renderBranches, menu: renderMenu, orders: renderOrders, requests: renderRequests, team: renderTeam };
  $('#content').innerHTML = renderers[state.route]();
}

function renderDashboard() {
  const { branches, activeOrders, pendingRequests } = counts();
  const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Dhaka' });
  const deliveredToday = state.data.orders.filter((order) => order.status === 'delivered' && String(order.createdAt || '').startsWith(today));
  const revenue = deliveredToday.reduce((sum, order) => sum + Number(order.totalAmountPaisa || 0), 0);
  const openBranches = branches.filter((branch) => branch.open === true && branch.acceptingOrders !== false);
  const recent = [...state.data.orders].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)).slice(0, 6);
  const coveragePercent = branches.length ? Math.round(openBranches.length / branches.length * 100) : 0;
  return `
    <div class="metric-grid">
      ${metric('LIVE ORDER QUEUE', activeOrders.length, `${state.data.orders.filter((o) => o.status === 'placed').length} waiting for a kitchen`, '◷', true)}
      ${metric('TODAY’S DELIVERED SALES', money(revenue), `${deliveredToday.length} completed orders`, '৳')}
      ${metric('BRANCHES ON AIR', `${openBranches.length} / ${branches.length}`, `${branches.length - openBranches.length} currently closed`, '⌖')}
      ${metric('AREA REQUESTS', pendingRequests.length, pendingRequests.length ? 'Customers are waiting for review' : 'No coverage requests pending', '↗')}
    </div>
    <div class="dashboard-grid">
      <div>
        <section class="panel">
          <div class="panel-head"><h3>Branch pulse</h3><button class="link" data-route="branches">Manage all →</button></div>
          <div class="branch-list">${branches.slice(0, 5).map(branchRow).join('') || empty('⌖', 'Create your first branch', 'Publish a kitchen pin and its delivery area to begin.')}</div>
        </section>
        <section class="panel">
          <div class="panel-head"><h3>Latest orders</h3><button class="link" data-route="orders">Open live queue →</button></div>
          <div class="activity-list">${recent.map((order) => `<div class="activity-item"><span class="activity-dot">${order.status === 'delivered' ? '✓' : '◷'}</span><div><p><strong>${esc(order.shortId || order.id)}</strong> · ${esc(order.customerName || 'Customer')}<small>${esc(order.branchName || order.branchId)} · ${relative(order.createdAt)}</small></p></div><span class="activity-amount">${money(order.totalAmountPaisa)}</span></div>`).join('') || '<p class="muted">No orders yet. New orders appear here in real time.</p>'}</div>
        </section>
      </div>
      <div class="coverage-panel">
        <section class="panel coverage-card">
          <p class="eyebrow eyebrow--light">NETWORK READINESS</p><h3>Coverage at a glance.</h3><p>Your branch network is one Undal brand. Each kitchen sees only the orders routed into its own delivery area.</p>
          <div class="coverage-number">${coveragePercent}%</div><p>of published branches are open on their own schedule</p>
          <div class="coverage-bar"><i style="width:${coveragePercent}%"></i></div><div class="coverage-legend"><span>${openBranches.length} online</span><span>${branches.length} total</span></div>
          <div class="quick-actions"><button data-action="new-branch"><span>＋</span>Add a branch</button><button data-route="menu"><span>▤</span>Edit the menu</button><button data-route="requests"><span>↗</span>Review areas</button><button data-route="team"><span>♙</span>Assign a team</button></div>
        </section>
      </div>
    </div>`;
}

function metric(label, value, note, icon, dark = false) {
  return `<article class="metric-card${dark ? ' metric-card--dark' : ''}"><div class="metric-label">${esc(label)}<span class="metric-icon">${icon}</span></div><div class="metric-value">${esc(value)}</div><p class="metric-note">${esc(note)}</p></article>`;
}

function branchRow(branch) {
  const orders = state.data.orders.filter((order) => (order.branchId || 'default') === branch.id && ['placed', 'accepted', 'ready', 'in_delivery'].includes(order.status));
  const isOpen = branch.active !== false && branch.open === true && branch.acceptingOrders !== false;
  return `<div class="branch-row"><div class="branch-identity"><span class="branch-monogram">${esc((branch.name || 'U')[0])}</span><div><strong>${esc(branch.name)}</strong><small>${esc(branch.storeAddressText || 'Sylhet')}</small></div></div><div class="branch-stat"><small>Delivery</small><strong>${km(branch.radiusKm)}</strong></div><div class="branch-stat"><small>Live orders</small><strong>${orders.length}</strong></div><div class="branch-stat"><small>Minimum</small><strong>${money(branch.minOrderAmountPaisa)}</strong></div>${statusPill(isOpen ? 'open' : 'closed')}</div>`;
}

function renderBranches() {
  const branches = [...state.data.branches].sort((a, b) => Number(a.priority || 100) - Number(b.priority || 100));
  return `<div class="section-head"><div><h2>One brand, many kitchens.</h2><p>Delivery coverage, hours, economics and staff — independently controlled per branch.</p></div><button class="button button--primary" data-action="new-branch">＋ Add branch</button></div>
    <div class="filter-bar"><div class="search-box"><input data-search placeholder="Search branch or area…" value="${esc(state.search)}" aria-label="Search branches"></div><div class="section-actions"><button class="button button--secondary" data-action="refresh">↻ Refresh</button></div></div>
    <div class="branch-grid">${branches.filter((branch) => `${branch.name} ${branch.storeAddressText} ${(branch.serviceAreas || []).join(' ')}`.toLowerCase().includes(state.search.toLowerCase())).map(branchCard).join('') || empty('⌖', 'No branches match', 'Try another area name, or add a new Undal branch.', '<button class="button button--primary" data-action="new-branch">Add branch</button>')}</div>`;
}

function branchCard(branch) {
  const live = state.data.orders.filter((order) => (order.branchId || 'default') === branch.id && ['placed', 'accepted', 'ready', 'in_delivery'].includes(order.status)).length;
  const open = branch.active !== false && branch.open === true && branch.acceptingOrders !== false;
  return `<article class="branch-card"><div class="branch-card-head"><div class="branch-card-title"><span class="branch-monogram">${esc((branch.name || 'U')[0])}</span><div><h3>${esc(branch.name)}</h3><p>${esc(branch.code || branch.id)} · PRIORITY ${Number(branch.priority ?? 100)}</p></div></div>${statusPill(branch.active === false ? 'inactive' : open ? 'open' : 'closed')}</div>
    <div class="branch-card-map" aria-hidden="true"><i class="coverage-ring"></i><i class="coverage-pin"></i></div>
    <div class="branch-card-stats"><div><small>Coverage</small><strong>${km(branch.radiusKm)}</strong></div><div><small>Live queue</small><strong>${live} orders</strong></div><div><small>Per km</small><strong>${money(branch.deliveryPerKmAmountPaisa)}</strong></div></div>
    <div class="branch-card-actions"><button class="button button--secondary button--small" data-edit-branch="${esc(branch.id)}">Edit branch</button><button class="button button--ghost button--small" data-branch-orders="${esc(branch.id)}">View orders →</button></div></article>`;
}

function renderMenu() {
  const branches = [{ id: '*', name: 'All branches (global menu)' }, ...state.data.branches];
  const items = state.data.menuItems.filter((item) => (state.branchFilter === 'all' || (item.branchId || '*') === state.branchFilter) && `${item.name} ${item.description || ''}`.toLowerCase().includes(state.search.toLowerCase()));
  const categories = Object.fromEntries(state.data.categories.map((category) => [category.id, category.name]));
  return `<div class="section-head"><div><h2>One library, local control.</h2><p>Global dishes belong to the Undal brand. Branch dishes appear only at the kitchen you choose.</p></div><button class="button button--primary" data-action="new-menu">＋ Add dish</button></div>
    <div class="filter-bar"><div class="search-box"><input data-search placeholder="Search dishes…" value="${esc(state.search)}" aria-label="Search dishes"></div><select data-branch-filter aria-label="Filter menu by branch">${branches.map((branch) => `<option value="${esc(branch.id)}" ${state.branchFilter === branch.id ? 'selected' : ''}>${esc(branch.name)}</option>`).join('')}<option value="all" ${state.branchFilter === 'all' ? 'selected' : ''}>Every menu entry</option></select><button class="button button--secondary" data-action="new-category">＋ Category</button></div>
    <div class="table-wrap"><table><thead><tr><th>Dish</th><th>Category</th><th>Branch</th><th>Price</th><th>Availability</th><th></th></tr></thead><tbody>${items.map((item) => `<tr><td><div class="item-cell"><span class="item-thumb">${item.imageUrl ? `<img src="${esc(item.imageUrl)}" alt="" loading="lazy">` : '♨'}</span><div><strong>${esc(item.name)}</strong><small>${esc((item.description || 'No description').slice(0, 55))}</small></div></div></td><td>${esc(categories[item.categoryId] || item.categoryId || 'Uncategorised')}</td><td>${esc(branchName(item.branchId || '*'))}</td><td><strong>${money(item.amountPaisa)}</strong>${Number(item.discountAmountPaisa) ? `<small>−${money(item.discountAmountPaisa)} discount</small>` : ''}</td><td>${statusPill(item.available === false ? 'sold out' : 'available')}</td><td><button class="button button--secondary button--small" data-edit-menu="${esc(item.id)}">Edit</button></td></tr>`).join('')}</tbody></table>${items.length ? '' : empty('▤', 'No dishes here yet', 'Add a dish or choose another branch filter.', '<button class="button button--primary" data-action="new-menu">Add dish</button>')}</div>`;
}

function branchName(id) {
  if (!id || id === '*') return 'All branches';
  return state.data.branches.find((branch) => branch.id === id)?.name || id;
}

function renderOrders() {
  const branches = [{ id: 'all', name: 'Every branch' }, ...state.data.branches];
  const filtered = state.data.orders.filter((order) => {
    const branchMatch = state.branchFilter === 'all' || (order.branchId || 'default') === state.branchFilter;
    const statusMatch = state.orderFilter === 'all' || (state.orderFilter === 'active' ? ['placed', 'accepted', 'ready', 'in_delivery'].includes(order.status) : order.status === state.orderFilter);
    const searchMatch = `${order.shortId} ${order.customerName} ${order.phone}`.toLowerCase().includes(state.search.toLowerCase());
    return branchMatch && statusMatch && searchMatch;
  }).sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
  return `<div class="section-head"><div><h2>Every order, right kitchen.</h2><p>Live branch queues with the same state machine used by the vendor app.</p></div><button class="button button--secondary" data-action="refresh">↻ Sync now</button></div>
    <div class="filter-bar"><div class="search-box"><input data-search placeholder="Order, customer or phone…" value="${esc(state.search)}" aria-label="Search orders"></div><select data-order-filter aria-label="Filter order status">${[['active','Active queue'],['placed','New'],['accepted','Preparing'],['ready','Ready'],['in_delivery','Out for delivery'],['delivered','Delivered'],['cancelled','Cancelled'],['all','All statuses']].map(([id,label]) => `<option value="${id}" ${state.orderFilter === id ? 'selected' : ''}>${label}</option>`).join('')}</select><select data-branch-filter aria-label="Filter orders by branch">${branches.map((branch) => `<option value="${branch.id}" ${state.branchFilter === branch.id ? 'selected' : ''}>${esc(branch.name)}</option>`).join('')}</select></div>
    <div class="table-wrap"><table><thead><tr><th>Order</th><th>Customer</th><th>Branch</th><th>Items</th><th>Total</th><th>Status</th><th>Action</th></tr></thead><tbody>${filtered.map((order) => `<tr><td><strong>${esc(order.shortId || order.id)}</strong><small>${shortDate(order.createdAt)}</small></td><td><strong>${esc(order.customerName)}</strong><small>${esc(order.phone)}</small></td><td>${esc(order.branchName || branchName(order.branchId))}</td><td>${Array.isArray(order.items) ? order.items.reduce((sum,item) => sum + Number(item.qty || 1),0) : 0}</td><td><strong>${money(order.totalAmountPaisa)}</strong><small>Cash on delivery</small></td><td>${statusPill(order.status)}</td><td>${orderAction(order)}</td></tr>`).join('')}</tbody></table>${filtered.length ? '' : empty('◷', 'No orders in this view', 'Try another status or branch. New orders arrive automatically.')}</div>`;
}

function orderAction(order) {
  const next = { placed: ['accepted', 'Accept · 25 min'], accepted: ['ready', 'Mark ready'], ready: ['in_delivery', 'Send rider'], in_delivery: ['delivered', 'Complete'] }[order.status];
  const buttons = [];
  if (next) buttons.push(`<button class="button button--primary button--small" data-order-next="${esc(order.id)}" data-status="${next[0]}">${next[1]}</button>`);
  if (['placed', 'accepted'].includes(order.status)) buttons.push(`<button class="button button--danger button--small" data-order-cancel="${esc(order.id)}">Cancel</button>`);
  return `<div class="table-actions">${buttons.join('') || '<span class="muted">Closed</span>'}</div>`;
}

function renderRequests() {
  const requests = [...state.data.deliveryRequests].sort((a, b) => (a.status === 'pending' ? -1 : 1) - (b.status === 'pending' ? -1 : 1) || Date.parse(b.createdAt) - Date.parse(a.createdAt));
  return `<div class="section-head"><div><h2>Grow where customers are.</h2><p>Each uncovered pin is assigned to its nearest branch. Approval expands real coverage immediately.</p></div><button class="button button--secondary" data-route="team">Manage branch team</button></div>
    <div class="request-grid">${requests.map((request) => `<article class="request-card"><div class="request-top"><div><h3>${esc(request.customerName || 'Undal customer')}</h3><p>${esc(request.branchName || branchName(request.branchId))} · ${relative(request.createdAt)}</p></div>${statusPill(request.status)}</div><p>${esc(request.address?.title || '')} · ${esc(request.address?.desc || '')}<br>${esc(request.address?.note || '')}</p><div class="request-measure"><div><strong>${km(request.distanceKm)}</strong><small>from branch</small></div><div><strong>${km(request.suggestedRadiusKm)}</strong><small>suggested radius</small></div></div>${request.status === 'pending' ? `<div class="request-actions"><button class="button button--success" data-request-decision="approved" data-request-id="${esc(request.id)}">Approve extension</button><button class="button button--danger" data-request-decision="rejected" data-request-id="${esc(request.id)}">Decline</button></div>` : `<p class="muted">Decision recorded ${shortDate(request.decidedAt || request.updatedAt)}</p>`}</article>`).join('') || empty('↗', 'No area requests', 'When a customer outside every delivery area asks for Undal, the request will arrive here for its nearest branch.')}</div>`;
}

function renderTeam() {
  return `<div class="section-head"><div><h2>The right people, the right kitchens.</h2><p>Vendor accounts are branch-scoped. Claims are server-issued and never editable by the app.</p></div><button class="button button--primary" data-action="new-staff">＋ Assign staff</button></div>
    <div class="panel"><div class="panel-head"><h3>Single vendor access policy</h3>${statusPill('protected')}</div><p class="muted">The super administrator manages all Undal branches. Branch staff use the separate <strong>Undal Vendor</strong> Android app and can operate only their assigned branches. After changing an assignment, the staff member signs out and back in to refresh Firebase claims.</p></div>
    <div class="table-wrap"><table><thead><tr><th>Team member</th><th>Firebase UID</th><th>Assigned branches</th><th>Platform</th><th></th></tr></thead><tbody>${state.data.staff.map((staff) => `<tr><td><strong>${esc(staff.name || 'Undal staff')}</strong><small>${esc(staff.email || '')}</small></td><td><code>${esc(staff.uid)}</code></td><td>${(staff.branchIds?.length ? staff.branchIds : ['default (legacy)']).map((id) => `<span class="status">${esc(branchName(id))}</span>`).join(' ')}</td><td>${esc(staff.platform || 'Android')}</td><td><button class="button button--secondary button--small" data-edit-staff="${esc(staff.uid)}">Edit access</button></td></tr>`).join('')}</tbody></table>${state.data.staff.length ? '' : empty('♙', 'No branch staff assigned', 'Provision a vendor login, then assign it to at least one branch.')}</div>`;
}

function openDialog(content) {
  state.map?.destroy();
  state.map = null;
  $('#dialog-content').innerHTML = content;
  const dialog = $('#editor-dialog');
  if (!dialog.open) dialog.showModal();
}
function closeDialog() {
  state.map?.destroy();
  state.map = null;
  $('#editor-dialog').close();
}
const dialogHead = (heading, copy) => `<div class="dialog-head"><div><h2>${esc(heading)}</h2><p>${esc(copy)}</p></div><button class="dialog-close" data-action="close-dialog" aria-label="Close editor">×</button></div>`;

function openBranchEditor(id) {
  const original = state.data.branches.find((branch) => branch.id === id);
  const branch = original || { name: '', code: '', active: true, acceptingOrders: false, open: false, storeLat: 24.8949, storeLng: 91.8687, storeAddressText: '', radiusKm: 5, deliveryPerKmAmountPaisa: 2000, minOrderAmountPaisa: 15000, priority: 100, weekly: Object.fromEntries(['sun','mon','tue','wed','thu','fri','sat'].map((day) => [day,[{open:'09:00',close:'22:00'}]])) };
  const days = [['sun','Sunday'],['mon','Monday'],['tue','Tuesday'],['wed','Wednesday'],['thu','Thursday'],['fri','Friday'],['sat','Saturday']];
  openDialog(`${dialogHead(original ? `Edit ${branch.name}` : 'Add an Undal branch', original ? 'Every change reaches customer and vendor apps live.' : 'A new kitchen for the same single vendor.')}
    <form id="branch-form" data-branch-id="${esc(id || '')}"><div class="dialog-body"><div class="form-grid">
      <label class="field">Branch / area name<input name="name" required minlength="2" maxlength="80" value="${esc(branch.name)}" placeholder="Ambarkhana"></label>
      <label class="field">Branch code<input name="code" maxlength="20" value="${esc(branch.code)}" placeholder="UND-AMB"></label>
      <label class="field span-2">Kitchen street address<input name="storeAddressText" required value="${esc(branch.storeAddressText)}" placeholder="House, road, area, Sylhet"></label>
      <div class="span-2"><label class="field">Kitchen pin & delivery radius</label><div id="branch-map"></div><div class="map-readout"><span>Centre pin: <strong id="map-coordinates">${Number(branch.storeLat).toFixed(6)}, ${Number(branch.storeLng).toFixed(6)}</strong></span><span>Drag map to reposition · radius follows the slider</span></div><input type="hidden" name="storeLat" value="${branch.storeLat}"><input type="hidden" name="storeLng" value="${branch.storeLng}"></div>
      <label class="field">Delivery radius (km)<input name="radiusKm" type="number" min="0.25" max="50" step="0.25" required value="${branch.radiusKm}"></label>
      <label class="field">Routing priority<input name="priority" type="number" min="0" max="9999" step="1" value="${branch.priority ?? 100}"><small class="help-text">Lower wins where branch areas overlap.</small></label>
      <label class="field">Delivery fee per km (paisa)<div class="input-prefix"><span>৳</span><input name="deliveryPerKmAmountPaisa" type="number" min="0" step="100" value="${branch.deliveryPerKmAmountPaisa}"></div></label>
      <label class="field">Minimum order (paisa)<div class="input-prefix"><span>৳</span><input name="minOrderAmountPaisa" type="number" min="0" step="100" value="${branch.minOrderAmountPaisa || 0}"></div></label>
      <label class="field span-2">Service area names (comma separated)<input name="serviceAreas" value="${esc((branch.serviceAreas || []).join(', '))}" placeholder="Ambarkhana, Dargah Gate, Chowhatta"></label>
      <div class="check-field"><input id="branch-active" name="active" type="checkbox" ${branch.active !== false ? 'checked' : ''}><label for="branch-active">Branch visible for automatic routing</label></div>
      <div class="check-field"><input id="branch-accepting" name="acceptingOrders" type="checkbox" ${branch.acceptingOrders === true ? 'checked' : ''}><label for="branch-accepting">Follow opening hours and accept orders</label></div>
      <input name="open" type="hidden" value="${branch.open === true}"><input name="autoClosed" type="hidden" value="${branch.autoClosed === true}">
      <label class="field span-2">Customer-facing pause note<input name="manualOverrideNote" value="${esc(branch.manualOverrideNote || '')}" placeholder="The kitchen opens shortly — please check back soon."></label>
    </div><div class="form-section"><h3>Weekly opening hours</h3><p>Asia/Dhaka. A closed day has no slots. The branch switch never opens a kitchen outside these hours.</p><div class="hours-grid">${days.map(([day,label]) => { const slot = branch.weekly?.[day]?.[0] || {open:'09:00',close:'22:00'}; const enabled = (branch.weekly?.[day]?.length || 0) > 0; return `<div class="hours-row" data-day="${day}"><strong>${label}</strong><input data-open type="time" value="${esc(slot.open)}" aria-label="${label} opening time"><span>→</span><input data-close type="time" value="${esc(slot.close)}" aria-label="${label} closing time"><label class="check-field"><input data-enabled type="checkbox" ${enabled ? 'checked' : ''}> Open</label></div>`; }).join('')}</div></div></div>
    <div class="dialog-footer"><button type="button" class="button button--secondary" data-action="close-dialog">Cancel</button><button type="submit" class="button button--primary">${original ? 'Publish branch changes' : 'Create branch'}</button></div></form>`);
  const form = $('#branch-form');
  const elements = form.elements;
  state.map = new BranchMap($('#branch-map'), { lat: Number(branch.storeLat), lng: Number(branch.storeLng), radiusKm: Number(branch.radiusKm), onChange: ({lat,lng}) => { elements.storeLat.value = lat; elements.storeLng.value = lng; $('#map-coordinates').textContent = `${lat.toFixed(6)}, ${lng.toFixed(6)}`; } });
  elements.radiusKm.addEventListener('input', () => state.map?.setValue({ radiusKm: Number(elements.radiusKm.value) }));
  form.addEventListener('submit', saveBranch);
}

async function saveBranch(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const id = form.dataset.branchId;
  const values = Object.fromEntries(new FormData(form));
  const weekly = {};
  $$('.hours-row', form).forEach((row) => { weekly[row.dataset.day] = $('[data-enabled]', row).checked ? [{ open: $('[data-open]', row).value, close: $('[data-close]', row).value }] : []; });
  const payload = { ...values, active: elementsChecked(form,'active'), acceptingOrders: elementsChecked(form,'acceptingOrders'), open: values.open === 'true', autoClosed: values.autoClosed === 'true', radiusKm: Number(values.radiusKm), storeLat: Number(values.storeLat), storeLng: Number(values.storeLng), deliveryPerKmAmountPaisa: Number(values.deliveryPerKmAmountPaisa), minOrderAmountPaisa: Number(values.minOrderAmountPaisa), priority: Number(values.priority), serviceAreas: String(values.serviceAreas || '').split(',').map((area) => area.trim()).filter(Boolean), weekly };
  const button = $('button[type="submit"]', form);
  setBusy(button, true, 'Publishing…');
  try {
    const result = await api(id ? `/api/admin/branches/${encodeURIComponent(id)}` : '/api/admin/branches', { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) });
    closeDialog();
    await refresh(false);
    toast(`${result.branch?.name || 'Branch'} is live in the Undal network.`);
  } catch (error) { toast(error.message, true); setBusy(button, false); }
}
function elementsChecked(form, name) { return form.elements[name]?.checked === true; }

function openMenuEditor(id) {
  const original = state.data.menuItems.find((item) => item.id === id);
  const item = original || { name:'', description:'', amountPaisa:25000, discountAmountPaisa:0, available:true, featured:false, veg:false, spiceLevel:0, branchId: state.branchFilter === 'all' ? '*' : state.branchFilter };
  openDialog(`${dialogHead(original ? `Edit ${item.name}` : 'Add a dish', 'Price is stored as exact paisa. Global dishes are available to every branch.')}
    <form id="menu-form" data-menu-id="${esc(id || '')}"><div class="dialog-body"><div class="form-grid">
      <label class="field">Dish name<input name="name" required minlength="2" value="${esc(item.name)}" placeholder="Undal special kacchi"></label>
      <label class="field">Category<select name="categoryId"><option value="">Uncategorised</option>${state.data.categories.map((category) => `<option value="${esc(category.id)}" ${item.categoryId === category.id ? 'selected' : ''}>${esc(category.name)}</option>`).join('')}</select></label>
      <label class="field span-2">Description<textarea name="description" maxlength="1000" placeholder="Ingredients, portion and what makes it good">${esc(item.description)}</textarea></label>
      <label class="field">Full price (paisa)<input name="amountPaisa" type="number" min="1" step="1" required value="${item.amountPaisa}"></label>
      <label class="field">Discount (paisa)<input name="discountAmountPaisa" type="number" min="0" step="1" value="${item.discountAmountPaisa || 0}"></label>
      <label class="field">Publish to<select name="branchId"><option value="*">All Undal branches</option>${state.data.branches.map((branch) => `<option value="${esc(branch.id)}" ${item.branchId === branch.id ? 'selected' : ''}>Only ${esc(branch.name)}</option>`).join('')}</select></label>
      <label class="field">Spice level<select name="spiceLevel">${[0,1,2,3].map((level) => `<option value="${level}" ${Number(item.spiceLevel) === level ? 'selected' : ''}>${['None','Mild','Medium','Hot'][level]}</option>`).join('')}</select></label>
      <label class="field span-2">Cloudinary image URL<input name="imageUrl" type="url" value="${esc(item.imageUrl || '')}" placeholder="https://res.cloudinary.com/…"></label>
      <label class="field span-2">Tags (comma separated)<input name="tags" value="${esc((item.tags || []).join(', '))}" placeholder="kacchi, beef, popular"></label>
      <div class="check-field"><input id="item-available" name="available" type="checkbox" ${item.available !== false ? 'checked' : ''}><label for="item-available">Available to order</label></div><div class="check-field"><input id="item-featured" name="featured" type="checkbox" ${item.featured === true ? 'checked' : ''}><label for="item-featured">Feature on storefront</label></div><div class="check-field"><input id="item-veg" name="veg" type="checkbox" ${item.veg === true ? 'checked' : ''}><label for="item-veg">Vegetarian</label></div>
    </div></div><div class="dialog-footer"><button type="button" class="button button--secondary" data-action="close-dialog">Cancel</button><button class="button button--primary" type="submit">Publish dish</button></div></form>`);
  $('#menu-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const values = Object.fromEntries(new FormData(form));
    const payload = { ...values, id: id || undefined, amountPaisa:Number(values.amountPaisa), discountAmountPaisa:Number(values.discountAmountPaisa), spiceLevel:Number(values.spiceLevel), available:elementsChecked(form,'available'), featured:elementsChecked(form,'featured'), veg:elementsChecked(form,'veg'), tags:String(values.tags || '').split(',').map((tag) => tag.trim()).filter(Boolean) };
    const button = $('button[type="submit"]',form); setBusy(button,true);
    try { await api('/api/admin/menu-items',{method:'POST',body:JSON.stringify(payload)}); closeDialog(); await refresh(false); toast('Dish published to the selected menu.'); } catch(error) { toast(error.message,true); setBusy(button,false); }
  });
}

function openCategoryEditor() {
  openDialog(`${dialogHead('Add a menu category','Categories organise every branch menu. Use “All branches” for the shared Undal library.')}<form id="category-form"><div class="dialog-body"><div class="form-grid"><label class="field">Category name<input name="name" required placeholder="Rice & biryani"></label><label class="field">Sort order<input name="order" type="number" value="100"></label><label class="field">Publish to<select name="branchId"><option value="*">All branches</option>${state.data.branches.map((branch) => `<option value="${esc(branch.id)}">${esc(branch.name)}</option>`).join('')}</select></label><div class="check-field"><input name="active" type="checkbox" checked><label>Visible on the menu</label></div></div></div><div class="dialog-footer"><button type="button" class="button button--secondary" data-action="close-dialog">Cancel</button><button class="button button--primary" type="submit">Create category</button></div></form>`);
  $('#category-form').addEventListener('submit', async (event) => { event.preventDefault(); const form=event.currentTarget; const values=Object.fromEntries(new FormData(form)); const button=$('button[type="submit"]',form); setBusy(button,true); try { await api('/api/admin/categories',{method:'POST',body:JSON.stringify({...values,order:Number(values.order),active:elementsChecked(form,'active')})}); closeDialog(); await refresh(false); toast('Category created.'); } catch(error) { toast(error.message,true); setBusy(button,false); } });
}

async function updateOrder(orderId, status, cancelNote) {
  if (status === 'cancelled' && !cancelNote) {
    const reason = prompt('Write the cancellation reason the customer will see:');
    if (!reason?.trim()) return;
    cancelNote = reason.trim();
  } else if (!confirm(`Move this order to ${title(status)}?`)) return;
  try { await api(`/api/admin/orders/${encodeURIComponent(orderId)}/status`,{method:'POST',body:JSON.stringify({status,prepMinutes:25,cancelNote})}); await refresh(false); toast(`Order moved to ${title(status)}.`); } catch(error) { toast(error.message,true); }
}

function openStaffEditor(uid) {
  const staff = state.data.staff.find((entry) => entry.uid === uid) || { uid:'',name:'',branchIds:[] };
  openDialog(`${dialogHead(uid ? 'Edit branch access' : 'Assign branch staff','The vendor must already have a Firebase Auth account. New claims apply at the next sign-in.')}<form id="staff-form"><div class="dialog-body"><div class="form-grid"><label class="field span-2">Firebase UID<input name="uid" required ${uid ? 'readonly' : ''} value="${esc(staff.uid)}" placeholder="Paste the vendor account UID"></label><label class="field span-2">Display name<input name="name" value="${esc(staff.name)}" placeholder="Branch kitchen manager"></label><div class="span-2"><label class="field">Assigned branches</label>${state.data.branches.map((branch) => `<div class="check-field"><input id="staff-${esc(branch.id)}" name="branchIds" type="checkbox" value="${esc(branch.id)}" ${staff.branchIds?.includes(branch.id) ? 'checked' : ''}><label for="staff-${esc(branch.id)}">${esc(branch.name)}</label></div>`).join('')}</div></div><p class="muted">Staff use the separate Undal Vendor app. They will not receive another branch’s orders, alerts or menu writes.</p></div><div class="dialog-footer"><button type="button" class="button button--secondary" data-action="close-dialog">Cancel</button><button class="button button--primary" type="submit">Save access</button></div></form>`);
  $('#staff-form').addEventListener('submit', async (event) => { event.preventDefault(); const form=event.currentTarget; const values=Object.fromEntries(new FormData(form)); const branchIds=$$('input[name="branchIds"]:checked',form).map((input) => input.value); const button=$('button[type="submit"]',form); setBusy(button,true); try { await api('/api/admin/staff',{method:'POST',body:JSON.stringify({uid:values.uid,name:values.name,branchIds})}); closeDialog(); await refresh(false); toast('Branch access updated. Ask staff to sign in again.'); } catch(error) { toast(error.message,true); setBusy(button,false); } });
}

// Global interaction delegation keeps rerendered live tables functional.
document.addEventListener('click', async (event) => {
  const button = event.target.closest('button,[data-route]');
  if (!button) return;
  if (button.dataset.route) return navigate(button.dataset.route);
  const action = button.dataset.action;
  if (action === 'close-dialog') return closeDialog();
  if (action === 'new-branch') return openBranchEditor();
  if (action === 'new-menu') return openMenuEditor();
  if (action === 'new-category') return openCategoryEditor();
  if (action === 'new-staff') return openStaffEditor();
  if (action === 'refresh' || action === 'retry') return refresh(true);
  if (button.dataset.editBranch) return openBranchEditor(button.dataset.editBranch);
  if (button.dataset.editMenu) return openMenuEditor(button.dataset.editMenu);
  if (button.dataset.editStaff) return openStaffEditor(button.dataset.editStaff);
  if (button.dataset.branchOrders) { state.branchFilter = button.dataset.branchOrders; state.orderFilter = 'active'; return navigate('orders'); }
  if (button.dataset.orderNext) return updateOrder(button.dataset.orderNext, button.dataset.status);
  if (button.dataset.orderCancel) return updateOrder(button.dataset.orderCancel, 'cancelled');
  if (button.dataset.requestDecision) {
    const decision = button.dataset.requestDecision;
    if (!confirm(`${decision === 'approved' ? 'Approve' : 'Decline'} this delivery-area request?`)) return;
    setBusy(button,true,'Recording…');
    try { await api(`/api/admin/delivery-requests/${encodeURIComponent(button.dataset.requestId)}`,{method:'POST',body:JSON.stringify({decision})}); await refresh(false); toast(`Request ${decision}.`); } catch(error) { toast(error.message,true); setBusy(button,false); }
  }
});

document.addEventListener('input', (event) => {
  if (event.target.matches('[data-search]')) { state.search = event.target.value; const position = event.target.selectionStart; render(); const next = $('[data-search]'); if (next) { next.focus(); next.setSelectionRange(position,position); } }
});
document.addEventListener('change', (event) => {
  if (event.target.matches('[data-branch-filter]')) { state.branchFilter = event.target.value; render(); }
  if (event.target.matches('[data-order-filter]')) { state.orderFilter = event.target.value; render(); }
});
$('#editor-dialog').addEventListener('cancel', () => { state.map?.destroy(); state.map = null; });
$('#refresh-button').addEventListener('click', () => refresh(true));
$('#logout-button').addEventListener('click', () => signOut());
window.addEventListener('popstate', () => { state.route = location.hash.slice(1) || 'dashboard'; render(); });

$('#login-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const button = $('button[type="submit"]',form);
  const errorNode = $('#login-error');
  errorNode.hidden = true;
  setBusy(button,true,'Verifying…');
  try {
    const result = await api('/api/admin/login',{method:'POST',body:JSON.stringify({email:form.elements.email.value,password:form.elements.password.value})});
    state.token = result.idToken;
    state.uid = result.uid;
    sessionStorage.setItem('undal_admin_token',state.token);
    sessionStorage.setItem('undal_admin_uid',state.uid);
    await api('/api/admin/session');
    form.reset();
    await enter();
  } catch(error) { errorNode.textContent = error.status === 403 ? 'This account is not an Undal super administrator.' : error.message; errorNode.hidden = false; state.token=''; sessionStorage.removeItem('undal_admin_token'); }
  finally { setBusy(button,false); }
});

if (state.token) enter();
