import { test, expect } from '@playwright/test';

test('deployed card discovery loads and search applies', async ({ page }) => {
  const failedResponses = [];
  const mutationRequests = [];
  page.on('response', (response) => {
    if (response.status() >= 500) failedResponses.push(response.status());
  });
  page.on('request', (request) => {
    if (/\/api\//.test(request.url()) && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
      mutationRequests.push(request.method());
    }
  });

  await page.goto('/cards', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 1, name: /cards|discover/i })).toBeVisible();
  const search = page.getByPlaceholder('Search cards by name...');
  await expect(search).toBeVisible();
  await search.fill('smoke-no-match');
  await page.getByRole('button', { name: /search|apply filters/i }).click();
  await expect(page.getByTestId('empty-state').getByRole('heading', { name: 'No cards found' })).toBeVisible();
  expect(failedResponses).toEqual([]);
  expect(mutationRequests).toEqual([]);
});

test('login form exposes labeled fields and password visibility control', async ({ page }) => {
  const mutationRequests = [];
  page.on('request', (request) => {
    if (/\/api\//.test(request.url()) && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
      mutationRequests.push(request.method());
    }
  });
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: 'Welcome back!' })).toBeVisible();
  await expect(page.getByLabel('Email')).toHaveAttribute('type', 'email');
  const password = page.getByLabel('Password');
  await expect(password).toHaveAttribute('type', 'password');
  await page.getByRole('button', { name: 'Show password' }).click();
  await expect(password).toHaveAttribute('type', 'text');
  await page.getByRole('button', { name: 'Hide password' }).click();
  await expect(password).toHaveAttribute('type', 'password');
  expect(mutationRequests).toEqual([]);
});
