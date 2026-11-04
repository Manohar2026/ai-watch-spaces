import { useAuth } from '../auth/AuthProvider';

export function Dashboard() {
  const { user, logout, isSubmitting } = useAuth();

  if (!user) {
    return null;
  }

  return (
    <main className="dashboard-shell">
      <header className="dashboard-topbar">
        <a className="brand" href="/" aria-label="AI Watch Spaces home">
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
          Your Watch Spaces account is connected to the Spring Boot backend.
          This is the starting point for your watch-space dashboard.
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
            <div><dt>Subtitle locale</dt><dd>{user.subtitleLocale}</dd></div>
            <div><dt>User ID</dt><dd className="user-id">{user.id}</dd></div>
          </dl>
        </article>

        <div className="next-step-card">
          <span className="next-step-icon">✦</span>
          <div>
            <h3>Authentication is connected.</h3>
            <p>Next we’ll add your recent watch spaces and catalogue using real backend API endpoints.</p>
          </div>
        </div>
      </section>
    </main>
  );
}
