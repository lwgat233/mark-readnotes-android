/* 覆盖自检：登记表 vs 界面真实 DOM vs 测试映射，六类缺口一次报全。
   判据是「读回来的事实」：DOM 里查得到才算挂上；登记了没做/没归属/没测都要点名。
   打开界面时自动跑一次（结果在 window.__coverage，验收脚本直接读它）。 */
(function () {
  'use strict';

  function run() {
    const R = window.MRN_REGISTRY;
    if (!R) return { error: '登记表没加载' };
    const boards = R.boards || [], feats = R.features || [];
    const gaps = { noBoard: [], emptyBoard: [], uiEmptyBoard: [], notMounted: [], noTest: [], planned: [] };

    boards.forEach(function (b) {
      if (b.planned) return;   // 计划中的板块不算缺口（它下面全是 planned 功能，界面上本来就不该有）
      const own = feats.filter(function (f) { return f.board === b.id; });
      if (own.length === 0) gaps.emptyBoard.push(b.id);
      else if (own.every(function (f) { return f.status === 'planned'; })) gaps.uiEmptyBoard.push(b.id);
    });

    feats.forEach(function (f) {
      if (!boards.some(function (b) { return b.id === f.board; })) gaps.noBoard.push(f.id);
      if (f.status === 'planned') { gaps.planned.push(f.id); return; }
      if (f.testId && !document.querySelector(f.testId)) gaps.notMounted.push(f.id + ' → ' + f.testId);
      if (!f.test) gaps.noTest.push(f.id);
    });

    const total = Object.keys(gaps).reduce(function (n, k) { return n + gaps[k].length; }, 0);
    window.__coverage = { gaps: gaps, total: total, boards: boards.length, features: feats.length };
    console.log('[自检] 缺口 ' + total + ' 项：' + JSON.stringify(gaps));
    return window.__coverage;
  }

  window.mrnSelfCheck = run;
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run);
  else run();
})();
