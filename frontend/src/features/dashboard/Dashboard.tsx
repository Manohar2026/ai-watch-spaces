import { useAuth } from '../auth/AuthProvider';

export function Dashboard({ onBrowseCatalog }: { onBrowseCatalog(): void }) {
  const { user, logout, isSubmitting } = useAuth();

  if (!user) {
    return null;
  }

  return (
    <main className="dashboard-shell">
      <header className="dashboard-topbar">
        <a className="brand" href="/" aria-label="Watch Spaces home">
          <span className="brand-mark" aria-hidden="true">W</span>
          <span>watch<span className="brand-accent">spaces</span></span>
        </a>
        <button className="dashboard-logout" type="button" onClick={() => void logout()} disabled={isSubmitting}>
          Sign out
        </button>
      </header>

      <section className="dashboard-content">
        <p className="eyebrow"><span className="status-dot" /> YOU'RE SIGNED IN</p>
        <h1>Welcome,<br /><span>{user.displayName}.</span></h1>
        <p className="dashboard-lead">
          Your account is ready. Browse the sample catalogue and choose a title to watch with friends.
        </p>

        <article className="account-card">
          <div className="account-card-heading">
            <div>
              <p className="eyebrow">YOUR ACCOUNT</p>
              <h2>Profile details</h2>
            </div>
            <span className="account-role">{user.systemRole}</span>
          </div>
          <dl className="account-details">
            <div><dt>Display name</dt><dd>{user.displayName}</dd></div>
            <div><dt>Email</dt><dd>{user.email}</dd></div>
            <div><dt>User ID</dt><dd className="user-id">{user.id}</dd></div>
          </dl>
        </article>

        <div className="dashboard-actions">
          <button className="primary-button" type="button" onClick={onBrowseCatalog}>
            Browse catalogue <span aria-hidden="true">→</span>
          </button>
        </div>

        <div className="next-step-card">
          <span className="next-step-icon">✦</span>
          <div>
            <h3>Your watch spaces are coming together.</h3>
            <p>Browse the catalogue now. Room creation, synchronized playback, chat, and presence are the next product features.</p>
          </div>
        </div>
      </section>
    </main>
  );
}

