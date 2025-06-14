<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Carpet PvP - Ultimate Minecraft Server Mod</title>
    <meta name="description" content="Carpet PvP - The ultimate Minecraft server mod with 100+ features, performance optimizations, and advanced debugging tools for competitive gaming.">
    
    <!-- Fonts -->
    <link href="https://fonts.googleapis.com/css2?family=Orbitron:wght@400;700;900&family=Inter:wght@300;400;500;600;700;800&display=swap" rel="stylesheet">
    
    <!-- Font Awesome -->
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
    
    <!-- AOS Animation Library -->
    <link href="https://unpkg.com/aos@2.3.1/dist/aos.css" rel="stylesheet">
    
    <style>
        :root {
            --primary-color: #00ff88;
            --primary-glow: rgba(0, 255, 136, 0.3);
            --secondary-color: #ff3366;
            --accent-color: #7c3aed;
            --accent-blue: #00d4ff;
            --bg-dark: #0a0a0f;
            --bg-darker: #000000;
            --bg-card: rgba(255, 255, 255, 0.02);
            --bg-card-hover: rgba(255, 255, 255, 0.05);
            --text-light: #ffffff;
            --text-gray: #a0a0a0;
            --text-muted: #6b7280;
            --border-glow: rgba(0, 255, 136, 0.2);
            --gradient-primary: linear-gradient(135deg, var(--primary-color), var(--accent-blue));
            --gradient-secondary: linear-gradient(135deg, var(--secondary-color), var(--accent-color));
        }

        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: 'Inter', sans-serif;
            background: var(--bg-darker);
            color: var(--text-light);
            overflow-x: hidden;
            line-height: 1.6;
        }

        /* Scroll behavior */
        html {
            scroll-behavior: smooth;
        }

        /* Dynamic background */
        .bg-animation {
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            z-index: -2;
            background: radial-gradient(circle at 20% 80%, rgba(0, 255, 136, 0.05) 0%, transparent 50%),
                        radial-gradient(circle at 80% 20%, rgba(124, 58, 237, 0.05) 0%, transparent 50%),
                        radial-gradient(circle at 40% 40%, rgba(255, 51, 102, 0.05) 0%, transparent 50%);
            animation: floatBg 20s ease-in-out infinite alternate;
        }

        @keyframes floatBg {
            0% { transform: translateY(0px) rotate(0deg); }
            100% { transform: translateY(-20px) rotate(2deg); }
        }

        /* Particles background */
        .particles {
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            z-index: -1;
            overflow: hidden;
        }

        .particle {
            position: absolute;
            width: 2px;
            height: 2px;
            background: var(--primary-color);
            border-radius: 50%;
            animation: particle-float 15s infinite linear;
            opacity: 0.3;
        }

        @keyframes particle-float {
            0% {
                transform: translateY(100vh) translateX(0px);
                opacity: 0;
            }
            10% {
                opacity: 0.3;
            }
            90% {
                opacity: 0.3;
            }
            100% {
                transform: translateY(-100px) translateX(100px);
                opacity: 0;
            }
        }

        /* Top Navigation */
        .navbar {
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            background: rgba(0, 0, 0, 0.9);
            backdrop-filter: blur(20px);
            border-bottom: 1px solid var(--border-glow);
            z-index: 1000;
            padding: 1rem 0;
            transition: all 0.3s ease;
        }

        .navbar.scrolled {
            background: rgba(0, 0, 0, 0.95);
            box-shadow: 0 10px 30px rgba(0, 255, 136, 0.1);
        }

        .nav-container {
            max-width: 1400px;
            margin: 0 auto;
            padding: 0 2rem;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }

        .nav-brand {
            display: flex;
            align-items: center;
            gap: 1rem;
            font-family: 'Orbitron', monospace;
            font-size: 1.5rem;
            font-weight: 900;
            color: var(--primary-color);
            text-decoration: none;
            text-shadow: 0 0 20px var(--primary-glow);
        }

        .nav-brand .logo {
            width: 40px;
            height: 40px;
            background: var(--gradient-primary);
            border-radius: 8px;
            display: flex;
            align-items: center;
            justify-content: center;
            box-shadow: 0 0 20px var(--primary-glow);
        }

        .nav-links {
            display: flex;
            gap: 2rem;
            list-style: none;
            align-items: center;
        }

        .nav-links a {
            color: var(--text-light);
            text-decoration: none;
            font-weight: 500;
            padding: 0.75rem 1.5rem;
            border-radius: 50px;
            transition: all 0.3s ease;
            position: relative;
            overflow: hidden;
        }

        .nav-links a::before {
            content: '';
            position: absolute;
            top: 0;
            left: -100%;
            width: 100%;
            height: 100%;
            background: var(--gradient-primary);
            transition: all 0.3s ease;
            z-index: -1;
        }

        .nav-links a:hover::before {
            left: 0;
        }

        .nav-links a:hover {
            color: var(--bg-darker);
            transform: translateY(-2px);
        }

        .nav-cta {
            background: var(--gradient-primary);
            color: var(--bg-darker);
            font-weight: 600;
            box-shadow: 0 10px 30px var(--primary-glow);
        }

        .nav-cta:hover {
            transform: translateY(-3px);
            box-shadow: 0 15px 40px var(--primary-glow);
        }

        /* Mobile menu */
        .mobile-menu-toggle {
            display: none;
            background: none;
            border: none;
            color: var(--text-light);
            font-size: 1.5rem;
            cursor: pointer;
        }

        /* Hero Section */
        .hero {
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            text-align: center;
            padding: 2rem;
            position: relative;
            overflow: hidden;
        }

        .hero-content {
            max-width: 1200px;
            z-index: 2;
        }

        .hero-badge {
            display: inline-block;
            background: var(--bg-card);
            border: 1px solid var(--border-glow);
            border-radius: 50px;
            padding: 0.5rem 1.5rem;
            margin-bottom: 2rem;
            font-size: 0.9rem;
            color: var(--primary-color);
            backdrop-filter: blur(10px);
            animation: pulse-glow 3s ease-in-out infinite;
        }

        @keyframes pulse-glow {
            0%, 100% { box-shadow: 0 0 20px var(--primary-glow); }
            50% { box-shadow: 0 0 40px var(--primary-glow); }
        }

        .hero h1 {
            font-family: 'Orbitron', monospace;
            font-size: clamp(3rem, 8vw, 6rem);
            font-weight: 900;
            background: var(--gradient-primary);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
            margin-bottom: 1.5rem;
            line-height: 1.1;
            text-shadow: 0 0 50px var(--primary-glow);
        }

        .hero-subtitle {
            font-size: clamp(1.2rem, 3vw, 1.8rem);
            color: var(--text-gray);
            margin-bottom: 3rem;
            max-width: 800px;
            margin-left: auto;
            margin-right: auto;
        }

        .hero-buttons {
            display: flex;
            gap: 2rem;
            justify-content: center;
            flex-wrap: wrap;
            margin-bottom: 4rem;
        }

        .btn {
            padding: 1rem 2.5rem;
            border: none;
            border-radius: 50px;
            font-size: 1.1rem;
            font-weight: 600;
            text-decoration: none;
            display: inline-flex;
            align-items: center;
            gap: 0.5rem;
            transition: all 0.3s ease;
            cursor: pointer;
            position: relative;
            overflow: hidden;
        }

        .btn-primary {
            background: var(--gradient-primary);
            color: var(--bg-darker);
            box-shadow: 0 10px 30px var(--primary-glow);
        }

        .btn-primary:hover {
            transform: translateY(-3px);
            box-shadow: 0 20px 40px var(--primary-glow);
        }

        .btn-secondary {
            background: transparent;
            color: var(--text-light);
            border: 2px solid var(--border-glow);
            backdrop-filter: blur(10px);
        }

        .btn-secondary:hover {
            background: var(--bg-card-hover);
            transform: translateY(-3px);
            box-shadow: 0 10px 30px rgba(255, 255, 255, 0.1);
        }

        /* Stats Section */
        .stats {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 2rem;
            margin-top: 4rem;
        }

        .stat-card {
            background: var(--bg-card);
            border: 1px solid var(--border-glow);
            border-radius: 20px;
            padding: 2rem;
            text-align: center;
            backdrop-filter: blur(10px);
            transition: all 0.3s ease;
        }

        .stat-card:hover {
            transform: translateY(-10px);
            box-shadow: 0 20px 40px var(--primary-glow);
        }

        .stat-number {
            font-family: 'Orbitron', monospace;
            font-size: 2.5rem;
            font-weight: 900;
            color: var(--primary-color);
            display: block;
            margin-bottom: 0.5rem;
        }

        .stat-label {
            color: var(--text-gray);
            font-size: 0.9rem;
            text-transform: uppercase;
            letter-spacing: 1px;
        }

        /* Features Section */
        .section {
            padding: 8rem 2rem;
            max-width: 1400px;
            margin: 0 auto;
        }

        .section-header {
            text-align: center;
            margin-bottom: 6rem;
        }

        .section-badge {
            display: inline-block;
            background: var(--gradient-secondary);
            color: var(--text-light);
            padding: 0.5rem 1.5rem;
            border-radius: 50px;
            font-size: 0.9rem;
            font-weight: 600;
            margin-bottom: 1rem;
            text-transform: uppercase;
            letter-spacing: 1px;
        }

        .section h2 {
            font-family: 'Orbitron', monospace;
            font-size: clamp(2.5rem, 5vw, 4rem);
            font-weight: 900;
            background: var(--gradient-primary);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
            margin-bottom: 1.5rem;
        }

        .section-description {
            font-size: 1.2rem;
            color: var(--text-gray);
            max-width: 600px;
            margin: 0 auto;
        }

        /* Feature Grid */
        .features-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
            gap: 3rem;
            margin-top: 4rem;
        }

        .feature-card {
            background: var(--bg-card);
            border: 1px solid var(--border-glow);
            border-radius: 24px;
            padding: 3rem;
            position: relative;
            overflow: hidden;
            transition: all 0.5s ease;
        }

        .feature-card::before {
            content: '';
            position: absolute;
            top: 0;
            left: 0;
            right: 0;
            height: 4px;
            background: var(--gradient-primary);
            transform: translateX(-100%);
            transition: all 0.5s ease;
        }

        .feature-card:hover::before {
            transform: translateX(0);
        }

        .feature-card:hover {
            transform: translateY(-10px);
            box-shadow: 0 30px 60px rgba(0, 0, 0, 0.3);
            border-color: var(--primary-color);
        }

        .feature-icon {
            width: 80px;
            height: 80px;
            background: var(--gradient-primary);
            border-radius: 20px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 2rem;
            color: var(--bg-darker);
            margin-bottom: 2rem;
            box-shadow: 0 10px 30px var(--primary-glow);
        }

        .feature-card h3 {
            font-family: 'Orbitron', monospace;
            font-size: 1.5rem;
            font-weight: 700;
            color: var(--text-light);
            margin-bottom: 1rem;
        }

        .feature-card p {
            color: var(--text-gray);
            margin-bottom: 2rem;
            line-height: 1.6;
        }

        .feature-list {
            list-style: none;
            padding: 0;
        }

        .feature-list li {
            display: flex;
            align-items: center;
            gap: 0.75rem;
            color: var(--text-gray);
            margin-bottom: 0.75rem;
            font-size: 0.95rem;
        }

        .feature-list li::before {
            content: '✓';
            color: var(--primary-color);
            font-weight: bold;
            font-size: 1.1rem;
        }

        /* Command Demo Section */
        .command-demo {
            background: var(--bg-card);
            border: 1px solid var(--border-glow);
            border-radius: 24px;
            padding: 3rem;
            margin: 4rem 0;
            position: relative;
            overflow: hidden;
        }

        .command-demo::before {
            content: '';
            position: absolute;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background: linear-gradient(45deg, transparent, rgba(0, 255, 136, 0.05), transparent);
            animation: shimmer 3s ease-in-out infinite;
        }

        @keyframes shimmer {
            0% { transform: translateX(-100%); }
            100% { transform: translateX(100%); }
        }

        .terminal-window {
            background: #1a1a1a;
            border-radius: 12px;
            overflow: hidden;
            box-shadow: 0 20px 40px rgba(0, 0, 0, 0.5);
        }

        .terminal-header {
            background: #2d2d2d;
            padding: 1rem;
            display: flex;
            align-items: center;
            gap: 0.5rem;
        }

        .terminal-dot {
            width: 12px;
            height: 12px;
            border-radius: 50%;
        }

        .terminal-dot.red { background: #ff5f57; }
        .terminal-dot.yellow { background: #ffbd2e; }
        .terminal-dot.green { background: #28ca42; }

        .terminal-content {
            padding: 2rem;
            font-family: 'Courier New', monospace;
            color: var(--text-light);
            line-height: 1.5;
        }

        .command-line {
            display: flex;
            align-items: center;
            margin-bottom: 1rem;
        }

        .prompt {
            color: var(--primary-color);
            margin-right: 0.5rem;
        }

        .command {
            color: var(--accent-blue);
        }

        .output {
            color: var(--text-gray);
            margin-left: 1rem;
            margin-bottom: 1rem;
        }

        /* Mockup Graphics */
        .feature-mockup {
            position: relative;
            margin: 2rem 0;
            padding: 2rem;
            background: var(--bg-card);
            border-radius: 16px;
            border: 1px solid var(--border-glow);
        }

        .mockup-hopper {
            width: 100%;
            height: 200px;
            background: linear-gradient(135deg, #4a4a4a, #2a2a2a);
            border-radius: 12px;
            position: relative;
            overflow: hidden;
        }

        .hopper-items {
            position: absolute;
            top: 50%;
            left: 20px;
            transform: translateY(-50%);
            display: flex;
            gap: 10px;
        }

        .item-cube {
            width: 24px;
            height: 24px;
            background: var(--gradient-primary);
            border-radius: 4px;
            animation: item-flow 2s ease-in-out infinite;
            box-shadow: 0 4px 8px rgba(0, 0, 0, 0.3);
        }

        .item-cube:nth-child(2) { animation-delay: 0.5s; }
        .item-cube:nth-child(3) { animation-delay: 1s; }

        @keyframes item-flow {
            0%, 100% { transform: translateX(0); }
            50% { transform: translateX(300px); }
        }

        .counter-display {
            position: absolute;
            top: 20px;
            right: 20px;
            background: var(--bg-darker);
            color: var(--primary-color);
            padding: 1rem;
            border-radius: 8px;
            font-family: 'Orbitron', monospace;
            font-weight: bold;
            border: 1px solid var(--primary-color);
            box-shadow: 0 0 20px var(--primary-glow);
        }

        /* Mobile Responsiveness */
        @media (max-width: 768px) {
            .nav-links {
                display: none;
            }

            .mobile-menu-toggle {
                display: block;
            }

            .hero-buttons {
                flex-direction: column;
                align-items: center;
            }

            .features-grid {
                grid-template-columns: 1fr;
                gap: 2rem;
            }

            .feature-card {
                padding: 2rem;
            }

            .stats {
                grid-template-columns: repeat(2, 1fr);
            }

            .section {
                padding: 4rem 1rem;
            }
        }

        /* Scroll animations */
        .fade-in {
            opacity: 0;
            transform: translateY(30px);
            transition: all 0.6s ease;
        }

        .fade-in.visible {
            opacity: 1;
            transform: translateY(0);
        }

        .slide-in-left {
            opacity: 0;
            transform: translateX(-50px);
            transition: all 0.6s ease;
        }

        .slide-in-left.visible {
            opacity: 1;
            transform: translateX(0);
        }

        .slide-in-right {
            opacity: 0;
            transform: translateX(50px);
            transition: all 0.6s ease;
        }

        .slide-in-right.visible {
            opacity: 1;
            transform: translateX(0);
        }
    </style>
</head>
<body>
    <div class="bg-animation"></div>
    
    <!-- Particle System -->
    <div class="particles" id="particles"></div>

    <!-- Navigation -->
    <nav class="navbar" id="navbar">
        <div class="nav-container">
            <a href="#" class="nav-brand">
                <div class="logo">
                    <i class="fas fa-cube"></i>
                </div>
                Carpet PvP
            </a>
            <ul class="nav-links">
                <li><a href="#home">Home</a></li>
                <li><a href="#features">Features</a></li>
                <li><a href="#commands">Commands</a></li>
                <li><a href="docs.html">Documentation</a></li>
                <li><a href="https://github.com/AndrewCTF/Carpet-PvP" target="_blank">GitHub</a></li>
                <li><a href="https://modrinth.com/mod/carpet-pvp-(updates)" target="_blank" class="nav-cta">Download</a></li>
            </ul>
            <button class="mobile-menu-toggle">
                <i class="fas fa-bars"></i>
            </button>
        </div>
    </nav>

    <!-- Hero Section -->
    <section class="hero" id="home">
        <div class="hero-content">
            <div class="hero-badge">
                <i class="fas fa-rocket"></i> Now Available for Minecraft 1.21.5
            </div>
            <h1>Carpet PvP</h1>
            <p class="hero-subtitle">
                The ultimate Minecraft server mod with 100+ features, performance optimizations, 
                and advanced debugging tools designed for competitive gaming and technical gameplay.
            </p>
            <div class="hero-buttons">
                <a href="https://modrinth.com/mod/carpet-pvp-(updates)" target="_blank" class="btn btn-primary">
                    <i class="fas fa-download"></i>
                    Download Now
                </a>
                <a href="docs.html" class="btn btn-secondary">
                    <i class="fas fa-book"></i>
                    View Documentation
                </a>
            </div>
            
            <div class="stats">
                <div class="stat-card fade-in">
                    <span class="stat-number">100+</span>
                    <span class="stat-label">Configurable Rules</span>
                </div>
                <div class="stat-card fade-in">
                    <span class="stat-number">15+</span>
                    <span class="stat-label">Advanced Commands</span>
                </div>
                <div class="stat-card fade-in">
                    <span class="stat-number">∞</span>
                    <span class="stat-label">Scarpet Scripts</span>
                </div>
                <div class="stat-card fade-in">
                    <span class="stat-number">24/7</span>
                    <span class="stat-label">Performance Boost</span>
                </div>
            </div>
        </div>
    </section>

    <!-- Features Section -->
    <section class="section" id="features">
        <div class="section-header">
            <div class="section-badge">Core Features</div>
            <h2>Powerful Tools for Minecraft Servers</h2>
            <p class="section-description">
                Everything you need to optimize, debug, and enhance your Minecraft server experience.
            </p>
        </div>

        <div class="features-grid">
            <div class="feature-card slide-in-left">
                <div class="feature-icon">
                    <i class="fas fa-calculator"></i>
                </div>
                <h3>Hopper Counters</h3>
                <p>Track item flow through your systems with precision counters that never miss a beat.</p>
                <div class="feature-mockup">
                    <div class="mockup-hopper">
                        <div class="hopper-items">
                            <div class="item-cube"></div>
                            <div class="item-cube"></div>
                            <div class="item-cube"></div>
                        </div>
                        <div class="counter-display">
                            Items: 1,337
                        </div>
                    </div>
                </div>
                <ul class="feature-list">
                    <li>16 separate color-coded channels</li>
                    <li>Real-time rate monitoring</li>
                    <li>Survival-friendly carpet interactions</li>
                    <li>Persistent across server restarts</li>
                </ul>
            </div>

            <div class="feature-card slide-in-right">
                <div class="feature-icon">
                    <i class="fas fa-user-robot"></i>
                </div>
                <h3>Fake Players</h3>
                <p>Spawn and control AI players for testing farms, redstone, and multiplayer mechanics.</p>
                <div class="command-demo">
                    <div class="terminal-window">
                        <div class="terminal-header">
                            <div class="terminal-dot red"></div>
                            <div class="terminal-dot yellow"></div>
                            <div class="terminal-dot green"></div>
                        </div>
                        <div class="terminal-content">
                            <div class="command-line">
                                <span class="prompt">></span>
                                <span class="command">/player TestBot spawn</span>
                            </div>
                            <div class="output">✓ Spawned fake player: TestBot</div>
                            <div class="command-line">
                                <span class="prompt">></span>
                                <span class="command">/player TestBot attack</span>
                            </div>
                            <div class="output">✓ TestBot is now attacking</div>
                        </div>
                    </div>
                </div>
                <ul class="feature-list">
                    <li>Full movement and action control</li>
                    <li>Inventory and item management</li>
                    <li>Perfect for farm testing</li>
                    <li>Multiplayer interaction simulation</li>
                </ul>
            </div>

            <div class="feature-card slide-in-left">
                <div class="feature-icon">
                    <i class="fas fa-chart-line"></i>
                </div>
                <h3>Performance Profiling</h3>
                <p>Real-time server monitoring and optimization tools to keep your server running smooth.</p>
                <ul class="feature-list">
                    <li>TPS monitoring and alerts</li>
                    <li>Entity performance tracking</li>
                    <li>Memory usage analysis</li>
                    <li>Bottleneck identification</li>
                    <li>Lag-free spawning optimization</li>
                </ul>
            </div>

            <div class="feature-card slide-in-right">
                <div class="feature-icon">
                    <i class="fas fa-ghost"></i>
                </div>
                <h3>Spawn Tracking</h3>
                <p>Advanced mob spawning analysis for optimizing farms and understanding spawn mechanics.</p>
                <ul class="feature-list">
                    <li>Real-time spawn attempt monitoring</li>
                    <li>Spawnable spot visualization</li>
                    <li>Mob cap tracking and analysis</li>
                    <li>Statistical spawn rate testing</li>
                    <li>128x128 area scanning</li>
                </ul>
            </div>

            <div class="feature-card slide-in-left">
                <div class="feature-icon">
                    <i class="fas fa-code"></i>
                </div>
                <h3>Scarpet Scripting</h3>
                <p>Custom automation and features with Carpet's powerful built-in scripting language.</p>
                <ul class="feature-list">
                    <li>Custom automation scripts</li>
                    <li>Event-driven programming</li>
                    <li>World interaction API</li>
                    <li>Community app store</li>
                    <li>Real-time script execution</li>
                </ul>
            </div>

            <div class="feature-card slide-in-right">
                <div class="feature-icon">
                    <i class="fas fa-cogs"></i>
                </div>
                <h3>100+ Rules</h3>
                <p>Extensive configuration options to tweak every aspect of your Minecraft server.</p>
                <ul class="feature-list">
                    <li>TNT and explosion customization</li>
                    <li>Redstone behavior modifications</li>
                    <li>Creative mode enhancements</li>
                    <li>Performance optimizations</li>
                    <li>Bug fixes and QoL improvements</li>
                </ul>
            </div>
        </div>
    </section>

    <!-- Commands Section -->
    <section class="section" id="commands">
        <div class="section-header">
            <div class="section-badge">Command Suite</div>
            <h2>Powerful Debug Commands</h2>
            <p class="section-description">
                Professional-grade tools for server administration and technical gameplay.
            </p>
        </div>

        <div class="command-demo">
            <div class="terminal-window">
                <div class="terminal-header">
                    <div class="terminal-dot red"></div>
                    <div class="terminal-dot yellow"></div>
                    <div class="terminal-dot green"></div>
                    <span style="margin-left: 1rem; color: #888; font-size: 0.9rem;">Minecraft Server Console</span>
                </div>
                <div class="terminal-content">
                    <div class="command-line">
                        <span class="prompt">></span>
                        <span class="command">/carpet hopperCounters true</span>
                    </div>
                    <div class="output">✓ Hopper counters enabled</div>
                    
                    <div class="command-line">
                        <span class="prompt">></span>
                        <span class="command">/counter white</span>
                    </div>
                    <div class="output">White counter: 1,337 items (45.2 items/min)</div>
                    
                    <div class="command-line">
                        <span class="prompt">></span>
                        <span class="command">/spawn tracking start ~ ~-10 ~ ~ ~10 ~</span>
                    </div>
                    <div class="output">✓ Started spawn tracking in 21x21 area</div>
                    
                    <div class="command-line">
                        <span class="prompt">></span>
                        <span class="command">/profile health 200</span>
                    </div>
                    <div class="output">✓ Profiling server health for 200 ticks...</div>
                    
                    <div class="command-line">
                        <span class="prompt">></span>
                        <span class="command">/log tps</span>
                    </div>
                    <div class="output">✓ Subscribed to TPS logger | Current: 20.0 TPS</div>
                </div>
            </div>
        </div>
    </section>

    <!-- CTA Section -->
    <section class="section">
        <div class="section-header">
            <div class="section-badge">Get Started</div>
            <h2>Ready to Supercharge Your Server?</h2>
            <p class="section-description">
                Join thousands of server owners using Carpet PvP to create the ultimate Minecraft experience.
            </p>
        </div>
        
        <div style="text-align: center; margin-top: 3rem;">
            <div class="hero-buttons">
                <a href="https://modrinth.com/mod/carpet-pvp-(updates)" target="_blank" class="btn btn-primary">
                    <i class="fas fa-download"></i>
                    Download from Modrinth
                </a>
                <a href="docs.html" class="btn btn-secondary">
                    <i class="fas fa-book-open"></i>
                    Read Full Documentation
                </a>
            </div>
        </div>
    </section>

    <!-- Footer -->
    <footer style="background: var(--bg-card); border-top: 1px solid var(--border-glow); padding: 3rem 2rem; text-align: center; color: var(--text-muted);">
        <div style="max-width: 1200px; margin: 0 auto;">
            <div style="display: flex; justify-content: center; gap: 2rem; margin-bottom: 2rem; flex-wrap: wrap;">
                <a href="https://github.com/AndrewCTF/Carpet-PvP" target="_blank" style="color: var(--primary-color); text-decoration: none;">
                    <i class="fab fa-github"></i> GitHub
                </a>
                <a href="https://modrinth.com/mod/carpet-pvp-(updates)" target="_blank" style="color: var(--primary-color); text-decoration: none;">
                    <i class="fas fa-download"></i> Modrinth
                </a>
                <a href="docs.html" style="color: var(--primary-color); text-decoration: none;">
                    <i class="fas fa-book"></i> Documentation
                </a>
            </div>
            <p>&copy; 2025 Carpet PvP. Built with ❤️ for the Minecraft community.</p>
            <p style="font-size: 0.9rem; margin-top: 1rem;">
                Based on the original Carpet Mod by gnembon | Minecraft 1.21.5 | Fabric
            </p>
        </div>
    </footer>

    <!-- AOS Animation Library -->
    <script src="https://unpkg.com/aos@2.3.1/dist/aos.js"></script>
    
    <script>
        // Initialize AOS
        AOS.init({
            duration: 800,
            easing: 'ease-in-out',
            once: true,
            offset: 50
        });

        // Particle system
        function createParticles() {
            const particlesContainer = document.getElementById('particles');
            const particleCount = 50;

            for (let i = 0; i < particleCount; i++) {
                const particle = document.createElement('div');
                particle.className = 'particle';
                particle.style.left = Math.random() * 100 + '%';
                particle.style.animationDelay = Math.random() * 15 + 's';
                particle.style.animationDuration = (Math.random() * 10 + 10) + 's';
                particlesContainer.appendChild(particle);
            }
        }

        // Navbar scroll effect
        function handleNavbarScroll() {
            const navbar = document.getElementById('navbar');
            if (window.scrollY > 100) {
                navbar.classList.add('scrolled');
            } else {
                navbar.classList.remove('scrolled');
            }
        }

        // Scroll animations
        function handleScrollAnimations() {
            const elements = document.querySelectorAll('.fade-in, .slide-in-left, .slide-in-right');
            
            elements.forEach(element => {
                const elementTop = element.getBoundingClientRect().top;
                const elementVisible = 150;
                
                if (elementTop < window.innerHeight - elementVisible) {
                    element.classList.add('visible');
                }
            });
        }

        // Smooth scrolling for navigation links
        function setupSmoothScrolling() {
            document.querySelectorAll('a[href^="#"]').forEach(anchor => {
                anchor.addEventListener('click', function (e) {
                    e.preventDefault();
                    const target = document.querySelector(this.getAttribute('href'));
                    if (target) {
                        target.scrollIntoView({
                            behavior: 'smooth',
                            block: 'start'
                        });
                    }
                });
            });
        }

        // Number counter animation
        function animateCounters() {
            const counters = document.querySelectorAll('.stat-number');
            const speed = 100;

            counters.forEach(counter => {
                const target = counter.textContent;
                if (target === '∞') return;
                
                const updateCount = () => {
                    const count = +counter.textContent.replace('+', '');
                    const targetNum = +target.replace('+', '');
                    const inc = targetNum / speed;

                    if (count < targetNum) {
                        counter.textContent = Math.ceil(count + inc) + (target.includes('+') ? '+' : '');
                        setTimeout(updateCount, 1);
                    } else {
                        counter.textContent = target;
                    }
                };

                // Start animation when element is visible
                const observer = new IntersectionObserver(entries => {
                    entries.forEach(entry => {
                        if (entry.isIntersecting) {
                            updateCount();
                            observer.unobserve(entry.target);
                        }
                    });
                });

                observer.observe(counter);
            });
        }

        // Initialize everything
        document.addEventListener('DOMContentLoaded', function() {
            createParticles();
            setupSmoothScrolling();
            animateCounters();
            handleScrollAnimations();
        });

        // Event listeners
        window.addEventListener('scroll', function() {
            handleNavbarScroll();
            handleScrollAnimations();
        });

        // Mobile menu toggle
        document.querySelector('.mobile-menu-toggle').addEventListener('click', function() {
            const navLinks = document.querySelector('.nav-links');
            navLinks.style.display = navLinks.style.display === 'flex' ? 'none' : 'flex';
        });
    </script>
</body>
</html>
