package main

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"image/color"
	"io"
	"net/http"
	"os"
	// "path/filepath" // УДАЛЕНО: не использовался
	"strings"
	"sync"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/widget"
	"github.com/miekg/dns"
)

// Global state variables
var (
	isRunning   bool
	stopChan    chan struct{}
	logWidget   *widget.Entry
	progressBar *CustomProgressBar
	window      fyne.Window
	mu          sync.Mutex
)

// --- Custom Progress Bar to satisfy color requirements ---
// Grey (idle), Blue (animating), Green (done)
type CustomProgressBar struct {
	widget.BaseWidget
	rect      *canvas.Rectangle
	bg        *canvas.Rectangle
	mode      int // 0: Idle (Grey), 1: Resolving (Blue), 2: Done (Green)
}

func NewCustomProgressBar() *CustomProgressBar {
	p := &CustomProgressBar{
		rect: canvas.NewRectangle(color.RGBA{100, 100, 100, 255}),
		bg:   canvas.NewRectangle(color.RGBA{40, 40, 40, 255}),
		mode: 0,
	}
	p.ExtendBaseWidget(p)
	return p
}

func (p *CustomProgressBar) CreateRenderer() fyne.WidgetRenderer {
	return &progressRenderer{p: p}
}

type progressRenderer struct {
	p *CustomProgressBar
}

func (r *progressRenderer) Layout(s fyne.Size) {
	r.p.bg.Resize(s)
	r.p.bg.Move(fyne.NewPos(0, 0))

	width := s.Width
	if r.p.mode == 1 { // Indeterminate/Resolving
		width = s.Width 
	} else if r.p.mode == 0 {
        width = 0
    }
	r.p.rect.Resize(fyne.NewSize(width, s.Height))
	r.p.rect.Move(fyne.NewPos(0, 0))
}

func (r *progressRenderer) MinSize() fyne.Size {
	return fyne.NewSize(100, 10)
}

func (r *progressRenderer) Refresh() {
	switch r.p.mode {
	case 0: // Idle - Grey
		r.p.rect.FillColor = color.RGBA{128, 128, 128, 255}
	case 1: // Resolving - Blue
		r.p.rect.FillColor = color.RGBA{64, 169, 255, 255}
	case 2: // Done - Green
		r.p.rect.FillColor = color.RGBA{76, 175, 80, 255}
	}
	r.p.rect.Refresh()
    canvas.Refresh(r.p)
}

func (r *progressRenderer) Objects() []fyne.CanvasObject {
	return []fyne.CanvasObject{r.p.bg, r.p.rect}
}

func (r *progressRenderer) Destroy() {}

// SetState: 0=Idle, 1=Running, 2=Done
func (p *CustomProgressBar) SetState(state int) {
	p.mode = state
	p.Refresh()
}

// --- Domain Structures ---

type Config struct {
	Address string
	Port    string
	IPv4    bool
	IPv6    bool
}

type DomainGroup struct {
	Comment string
	Domains []string
}

// --- Main Application ---

func main() {
	a := app.New()
	a.Settings().SetTheme(&androidTheme{})

	window = a.NewWindow("DNStoHOSTS")
	window.Resize(fyne.NewSize(400, 600))

	// UI Components
	logWidget = widget.NewMultiLineEntry()
	// ИСПРАВЛЕНО: ReadOnly заменено на Disable, но цвет текста исправлен в theme.go
	logWidget.Disable() 
	logWidget.TextStyle = fyne.TextStyle{Monospace: true}
	logWidget.Wrapping = fyne.TextWrapWord
    
    // Buttons
	btnStart := widget.NewButton("Start", func() {
		startResolving()
	})
	btnStop := widget.NewButton("Stop", func() {
		stopResolving()
	})
	btnClear := widget.NewButton("Clear Log", func() {
		// Для изменения текста в Disabled виджете нужно на мгновение включить его
		logWidget.Enable()
		logWidget.SetText("")
		logWidget.Disable()
        progressBar.SetState(0) // Reset to grey
	})

    // Progress Bar
	progressBar = NewCustomProgressBar()

	// Layout
	buttonContainer := container.NewGridWithColumns(3, btnStart, btnStop, btnClear)
    
    logScroll := container.NewScroll(logWidget)
    
    // ИСПРАВЛЕНО: Удалена неиспользуемая переменная bottomContainerWithMin
    // Bottom container
    // bottomContainer := container.New(layout.NewBorderLayout(nil, nil, nil, nil), progressBar)

	content := container.New(layout.NewBorderLayout(buttonContainer, progressBar, nil, nil),
		buttonContainer,
		progressBar,
		logScroll,
	)

	window.SetContent(content)
	window.ShowAndRun()
}

// --- Logic ---

func appendLog(msg string) {
	// Must be on main thread
	// Трюк для обновления текста в отключенном (read-only) виджете
	logWidget.Enable()
	
	// Get current time
    t := time.Now().Format("15:04:05")
    fullMsg := fmt.Sprintf("[%s] %s\n", t, msg)
    
    logWidget.SetText(logWidget.Text + fullMsg)
	logWidget.CursorRow = len(strings.Split(logWidget.Text, "\n"))
	
	logWidget.Disable()
	logWidget.Refresh()
}

func startResolving() {
	mu.Lock()
	if isRunning {
		mu.Unlock()
		return
	}
	isRunning = true
	stopChan = make(chan struct{})
	mu.Unlock()

    progressBar.SetState(1) // Blue

	go func() {
		defer func() {
			mu.Lock()
			isRunning = false
			mu.Unlock()
		}()

		appendLog("Starting to resolve domains...")
        
        // 1. Read Settings
		appendLog("Reading settings.txt...")
        config, err := readSettings()
        if err != nil {
            appendLog(fmt.Sprintf("Error reading settings: %v", err))
            progressBar.SetState(0)
            return
        }
        appendLog(fmt.Sprintf("DNS Server: %s", config.Address))
        appendLog(fmt.Sprintf("IPv4: %v, IPv6: %v", config.IPv4, config.IPv6))

        // 2. Read Input
        appendLog("Reading input.txt...")
        domainGroups, totalDomains, err := readInput()
        if err != nil {
             appendLog(fmt.Sprintf("Error reading input: %v", err))
             progressBar.SetState(0)
             return
        }
        appendLog(fmt.Sprintf("Found %d domains to resolve", totalDomains))
        appendLog("----------------------------------------")

        // 3. Resolve
        var outputLines []string
        
        // Context for cancellation
        ctx, cancel := context.WithCancel(context.Background())
        go func() {
            <-stopChan
            cancel()
        }()

        for _, group := range domainGroups {
            // Check stop
            select {
            case <-ctx.Done():
                appendLog("Stop requested, waiting for current operation to complete...")
                appendLog("Operation cancelled by user")
                progressBar.SetState(0)
                return
            default:
            }

            if group.Comment != "" {
                appendLog(group.Comment)
                outputLines = append(outputLines, group.Comment)
            }

            for _, d := range group.Domains {
                select {
                case <-ctx.Done():
                    appendLog("Operation cancelled by user")
                    progressBar.SetState(0)
                    return
                default:
                }

                appendLog(fmt.Sprintf("Resolving: %s", d))
                
                ips := resolveDNSBinary(ctx, d, config)
                
                if len(ips) == 0 {
                     appendLog(fmt.Sprintf("  No records found for %s", d))
                     outputLines = append(outputLines, fmt.Sprintf("# No records found: %s", d))
                } else {
                    for _, ip := range ips {
                        appendLog(fmt.Sprintf("  %s %s", ip, d))
                        outputLines = append(outputLines, fmt.Sprintf("%s %s", ip, d))
                    }
                }
            }
        }
        
        appendLog("----------------------------------------")
        appendLog("Writing output.txt...")
        
        err = writeOutput(outputLines)
        if err != nil {
            appendLog(fmt.Sprintf("Error writing output: %v", err))
            progressBar.SetState(0) // Back to idle/error
        } else {
            appendLog(fmt.Sprintf("Successfully wrote %d lines to output.txt", len(outputLines)))
            progressBar.SetState(2) // Green
        }
	}()
}

func stopResolving() {
	mu.Lock()
	if isRunning {
		close(stopChan)
	}
	mu.Unlock()
}

// --- File Operations ---

func readSettings() (Config, error) {
    cfg := Config{
        Port: "443", // Default DoH port usually
        IPv4: true,
        IPv6: false,
    }
    
    path := "settings.txt" 
    file, err := os.Open(path)
    if err != nil {
        return cfg, err
    }
    defer file.Close()
    
    scanner := bufio.NewScanner(file)
    for scanner.Scan() {
        line := strings.TrimSpace(scanner.Text())
        if strings.HasPrefix(line, "#") || line == "" {
            continue
        }
        parts := strings.SplitN(line, "=", 2)
        if len(parts) != 2 {
            continue
        }
        key := strings.TrimSpace(parts[0])
        val := strings.TrimSpace(parts[1])
        
        switch key {
        case "adress":
            cfg.Address = val
        case "port":
            cfg.Port = val
        case "ipv4":
            cfg.IPv4 = (val == "true")
        case "ipv6":
            cfg.IPv6 = (val == "true")
        }
    }
    return cfg, scanner.Err()
}

func readInput() ([]DomainGroup, int, error) {
    path := "input.txt"
    file, err := os.Open(path)
    if err != nil {
        return nil, 0, err
    }
    defer file.Close()
    
    var groups []DomainGroup
    var currentGroup DomainGroup
    
    scanner := bufio.NewScanner(file)
    for scanner.Scan() {
        line := strings.TrimSpace(scanner.Text())
        if line == "" {
            continue
        }
        
        if strings.HasPrefix(line, "#") {
            if len(currentGroup.Domains) > 0 {
                groups = append(groups, currentGroup)
                currentGroup = DomainGroup{}
            }
            currentGroup.Comment = line
        } else {
            currentGroup.Domains = append(currentGroup.Domains, line)
        }
    }
    if len(currentGroup.Domains) > 0 || currentGroup.Comment != "" {
        groups = append(groups, currentGroup)
    }
    
    // Calculate total domains
    total := 0
    for _, g := range groups {
        total += len(g.Domains)
    }
    
    return groups, total, scanner.Err()
}

func writeOutput(lines []string) error {
    path := "output.txt"
    f, err := os.Create(path)
    if err != nil {
        return err
    }
    defer f.Close()
    
    w := bufio.NewWriter(f)
    for _, line := range lines {
        fmt.Fprintln(w, line)
    }
    return w.Flush()
}

// --- DNS Logic (Binary over HTTPS) ---

func resolveDNSBinary(ctx context.Context, domainName string, cfg Config) []string {
    var results []string
    
    query := func(qType uint16) {
        m := new(dns.Msg)
        m.SetQuestion(dns.Fqdn(domainName), qType)
        m.RecursionDesired = true
        
        data, err := m.Pack()
        if err != nil {
            appendLog(fmt.Sprintf("Error packing DNS: %v", err))
            return
        }
        
        url := fmt.Sprintf("https://%s:%s/dns-query", cfg.Address, cfg.Port)
        
        req, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewReader(data))
        if err != nil {
            appendLog(fmt.Sprintf("Error creating request: %v", err))
            return
        }
        
        req.Header.Set("Content-Type", "application/dns-message")
        req.Header.Set("Accept", "application/dns-message")
        
        client := &http.Client{
            Timeout: 10 * time.Second,
        }
        
        resp, err := client.Do(req)
        if err != nil {
            return
        }
        defer resp.Body.Close()
        
        if resp.StatusCode != http.StatusOK {
            return
        }
        
        body, err := io.ReadAll(resp.Body)
        if err != nil {
            return
        }
        
        respMsg := new(dns.Msg)
        err = respMsg.Unpack(body)
        if err != nil {
             appendLog(fmt.Sprintf("Error unpacking DNS: %v", err))
             return
        }
        
        for _, ans := range respMsg.Answer {
            switch t := ans.(type) {
            case *dns.A:
                results = append(results, t.A.String())
            case *dns.AAAA:
                results = append(results, t.AAAA.String())
            }
        }
    }

    if cfg.IPv4 {
        query(dns.TypeA)
    }
    if cfg.IPv6 {
        query(dns.TypeAAAA)
    }
    
    return results
}
