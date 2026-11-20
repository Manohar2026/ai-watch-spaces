import { expect, test, type Page } from '@playwright/test';

const hostEmail = process.env.E2E_HOST_EMAIL ?? 'e2e-host@example.test';
const hostPassword = process.env.E2E_HOST_PASSWORD ?? 'E2E-Password-123!';

async function avoidExternalSampleVideo(page: Page) {
  await page.route('https://interactive-examples.mdn.mozilla.net/**', (route) => route.abort());
}

async function signIn(page: Page, email: string, password: string) {
  await page.goto('/');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Welcome,/ })).toBeVisible();
}

async function registerThroughUi(page: Page, displayName: string, email: string, password: string) {
  await page.goto('/');
  await page.getByRole('button', { name: 'Get started' }).click();
  await page.getByLabel('Display name').fill(displayName);
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('Your account has been created');
  await expect(page.getByRole('heading', { name: 'Good to see you again.' })).toBeVisible();
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Welcome,/ })).toBeVisible();
}

async function browseCatalogue(page: Page) {
  await page.getByRole('button', { name: /Browse catalogue/ }).click();
  await expect(page.getByRole('heading', { name: 'Explore the catalogue.' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'The First Bloom' })).toBeVisible();
}

test('two people can register, create and join a room, sync playback, chat, and leave', async ({ browser }) => {
  const hostContext = await browser.newContext();
  const guestContext = await browser.newContext();
  const hostPage = await hostContext.newPage();
  const guestPage = await guestContext.newPage();
  await avoidExternalSampleVideo(hostPage);
  await avoidExternalSampleVideo(guestPage);

  const guestEmail = `e2e-guest-${Date.now()}@example.test`;
  const guestPassword = 'Guest-Password-123!';

  try {
    // Existing seed owner signs in and creates a room from the real sample catalogue.
    await signIn(hostPage, hostEmail, hostPassword);
    await browseCatalogue(hostPage);
    await hostPage.getByRole('button', { name: /View details/ }).first().click();

    const titleDialog = hostPage.getByRole('dialog', { name: 'Title details' });
    await expect(titleDialog.getByRole('heading', { name: 'The First Bloom' })).toBeVisible();
    await titleDialog.getByRole('button', { name: /Create a watch space/ }).click();
    await expect(hostPage.getByRole('heading', { name: 'Invite your people.' })).toBeVisible();

    const inviteCode = (await hostPage.locator('.invite-code-box code').textContent())?.trim();
    expect(inviteCode).toBeTruthy();

    // A second user completes registration in the browser and joins with the invite code.
    await registerThroughUi(guestPage, 'E2E Guest', guestEmail, guestPassword);
    await browseCatalogue(guestPage);
    await guestPage.getByRole('button', { name: 'Join a space' }).click();
    await guestPage.getByLabel('Invite code').fill(inviteCode!);
    await guestPage.getByRole('button', { name: 'Join space' }).click();

    await expect(guestPage.getByText('VIEWER', { exact: true })).toBeVisible();
    await expect(hostPage.getByText('E2E Guest', { exact: true })).toBeVisible();

    // Host starts the room and changes the authoritative playback state.
    await hostPage.getByRole('button', { name: 'Start the room' }).click();
    await expect(hostPage.getByText('ROOM IS LIVE')).toBeVisible();
    await expect(guestPage.getByText('Following the host · Paused')).toBeVisible();

    await hostPage.getByRole('button', { name: 'Play for everyone' }).click();
    await expect(hostPage.getByRole('button', { name: 'Pause for everyone' })).toBeVisible();
    await expect(guestPage.getByText('Following the host · Playing')).toBeVisible();

    // Chat is durable: the guest receives a message from the host through room polling.
    const message = 'The first bloom looks beautiful!';
    await hostPage.getByRole('textbox', { name: 'Chat message' }).fill(message);
    await hostPage.getByRole('button', { name: 'Send' }).click();
    await expect(hostPage.getByText(message)).toBeVisible();
    await expect(guestPage.getByText(message)).toBeVisible({ timeout: 10_000 });

    // The viewer leaves through the API; the host sees the participant disappear.
    await guestPage.locator('.room-top-actions').getByRole('button', { name: 'Leave room' }).click();
    await expect(guestPage.getByRole('heading', { name: 'Explore the catalogue.' })).toBeVisible();
    await expect(hostPage.getByText('E2E Guest', { exact: true })).toHaveCount(0);

    // Ending the room returns its host to the catalogue and closes the lifecycle.
    await hostPage.getByRole('button', { name: 'End room' }).click();
    await expect(hostPage.getByRole('heading', { name: 'Explore the catalogue.' })).toBeVisible();
  } finally {
    await guestContext.close();
    await hostContext.close();
  }
});

test('catalogue genre filter waits for search submission', async ({ page }) => {
  await signIn(page, hostEmail, hostPassword);

  const titleRequests: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/titles?')) titleRequests.push(request.url());
  });

  await browseCatalogue(page);
  await expect.poll(() => titleRequests.length).toBeGreaterThan(0);
  const initialRequestCount = titleRequests.length;

  await page.getByLabel('Filter by genre').fill('nature');
  await page.waitForTimeout(300);
  expect(titleRequests).toHaveLength(initialRequestCount);

  await page.getByRole('button', { name: /Search/ }).click();
  await expect.poll(() => titleRequests.length).toBe(initialRequestCount + 1);
  expect(titleRequests.at(-1)).toContain('genre=nature');
});
