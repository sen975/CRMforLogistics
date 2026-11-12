import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import ts from 'typescript';

const routerUrl = new URL('../src/router.tsx', import.meta.url);
const routerSource = readFileSync(routerUrl, 'utf8');
const router = ts.createSourceFile(
  routerUrl.pathname,
  routerSource,
  ts.ScriptTarget.Latest,
  true,
  ts.ScriptKind.TSX,
);

const routeModules = [
  './components/AppLayout',
  './pages/LoginPage',
  './pages/HomePage',
  './pages/ThreadPage',
  './pages/SendPage',
  './pages/TemplatesPage',
  './pages/ChannelSettingsPage',
  './pages/PhoneRepositoryPage',
];

function inspectRouter() {
  const staticImports = new Set();
  const dynamicImports = new Set();
  const jsxTags = new Set();
  const routeProperties = new Set();

  const visit = (node) => {
    if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) {
      staticImports.add(node.moduleSpecifier.text);
    }
    if (
      ts.isCallExpression(node)
      && node.expression.kind === ts.SyntaxKind.ImportKeyword
      && node.arguments.length === 1
      && ts.isStringLiteral(node.arguments[0])
    ) {
      dynamicImports.add(node.arguments[0].text);
    }
    if (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) {
      jsxTags.add(node.tagName.getText(router));
    }
    if (ts.isPropertyAssignment(node)) {
      routeProperties.add(node.name.getText(router));
    }
    ts.forEachChild(node, visit);
  };

  visit(router);
  return { staticImports, dynamicImports, jsxTags, routeProperties };
}

function compactSource(source) {
  return source.replace(/\s+/g, ' ');
}

test('路由级代码拆分：页面和布局只通过动态导入加载', () => {
  const { staticImports, dynamicImports } = inspectRouter();

  for (const routeModule of routeModules) {
    assert.equal(
      staticImports.has(routeModule),
      false,
      `${routeModule} 仍被静态导入`,
    );
    assert.equal(
      dynamicImports.has(routeModule),
      true,
      `${routeModule} 缺少动态导入`,
    );
  }
});

test('路由级代码拆分：共用加载态和失败恢复由路由层提供', () => {
  const { jsxTags, routeProperties } = inspectRouter();

  assert.equal(jsxTags.has('Suspense'), true, '缺少共用 Suspense 加载边界');
  assert.equal(jsxTags.has('Spin'), true, '缺少路由加载指示器');
  assert.equal(routeProperties.has('errorElement'), true, '缺少路由错误元素');
  assert.match(routerSource, /页面加载失败/);
  assert.match(routerSource, /重新加载/);
  assert.match(routerSource, /window\.location\.reload\(\)/);
});

test('路由级代码拆分：整页与内容区使用正确的加载高度', () => {
  const compactRouter = compactSource(routerSource);

  assert.doesNotMatch(routerSource, /100vh\s*-\s*64px/);
  assert.match(routerSource, /fullPage\s*\?\s*'100vh'\s*:\s*'100%'/);
  assert.match(
    compactRouter,
    /<AuthProvider> <RouteBoundary fullPage> <LoginPage \/> <\/RouteBoundary> <\/AuthProvider>/,
  );
  assert.match(
    compactRouter,
    /<AuthProvider> <AuthGuard> <RouteBoundary fullPage> <AppLayout \/> <\/RouteBoundary> <\/AuthGuard> <\/AuthProvider>/,
  );
});

test('路由级代码拆分：既有路由、鉴权和错误出口保持完整', () => {
  const compactRouter = compactSource(routerSource);
  const childRoutes = [
    ["index: true", 'HomePage'],
    ["path: 'conversations/contact/:contactId'", 'ConversationWorkspace'],
    ["path: 'send'", 'SendPage'],
    ["path: 'templates'", 'TemplatesPage'],
    ["path: 'settings/channels'", 'ChannelSettingsPage'],
    ["path: 'phone-repository'", 'PhoneRepositoryPage'],
  ];

  assert.match(routerSource, /if \(!token\) return <Navigate to="\/login" replace \/>/);
  assert.equal((routerSource.match(/errorElement:\s*<RouteLoadError\s*\/>/g) ?? []).length, 2);
  for (const [routeProperty, component] of childRoutes) {
    assert.match(
      compactRouter,
      new RegExp(`\\{ ${routeProperty.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}, element: <RouteBoundary><${component} \\/><\\/RouteBoundary> \\}`),
    );
  }
  assert.match(compactRouter, /\{ path: 'thread\/:contactId', element: <LegacyThreadRedirect \/> \}/);
});
