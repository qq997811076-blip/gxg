package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	testDuration     = 10 * time.Second
	idleTimeout      = 3 * time.Second
	downloadLimit    int64 = 50 * 1024 * 1024
	readBufferSize         = 32 * 1024
	minDownloadBytes int64 = 64 * 1024
	minMeasureTime         = 100 * time.Millisecond
	minSpeed               = 0.5
	connectTimeout         = 5 * time.Second
	sourceTimeout          = 60 * time.Second
)

var (
	sourceURLs = []string{
		"https://zip.cm.edu.kg/all.txt",
		"https://countrymerge.pages.dev/all.txt",
	}

	ipv4Pattern = regexp.MustCompile(
		`[0-9]{1,3}(?:\.[0-9]{1,3}){3}`,
	)
)

type Result struct {
	IP      string
	Speed   float64
	Bytes   int64
	Used    float64
	Latency time.Duration
}

func extractIPs(text string) []string {
	matches := ipv4Pattern.FindAllStringIndex(text, -1)
	ips := make([]string, 0)
	seen := make(map[string]bool)

	for _, match := range matches {
		start, end := match[0], match[1]

		if start > 0 {
			c := text[start-1]
			if (c >= '0' && c <= '9') || c == '.' {
				continue
			}
		}

		if end < len(text) {
			c := text[end]
			if (c >= '0' && c <= '9') || c == '.' {
				continue
			}
		}

		parsed := net.ParseIP(text[start:end])
		if parsed == nil || parsed.To4() == nil {
			continue
		}

		ip := parsed.To4().String()
		if !seen[ip] {
			seen[ip] = true
			ips = append(ips, ip)
		}
	}

	return ips
}

// 读取本地 ips.txt；文件不存在时跳过，不修改本地文件。
func loadLocalIPs(filename string) ([]string, error) {
	data, err := os.ReadFile(filename)
	if err != nil {
		if os.IsNotExist(err) {
			fmt.Println("本地 ips.txt 不存在，跳过本地来源")
			return nil, nil
		}
		return nil, err
	}

	ips := extractIPs(string(data))
	fmt.Printf("本地 ips.txt：识别到 %d 个唯一 IPv4\n", len(ips))
	return ips, nil
}

// 从网络读取完整文本，仅在内存中提取 IP，不保存下载文件。
func fetchIPs(sourceURL string) ([]string, error) {
	ctx, cancel := context.WithTimeout(
		context.Background(), sourceTimeout,
	)
	defer cancel()

	req, err := http.NewRequestWithContext(
		ctx, http.MethodGet, sourceURL, nil,
	)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0")

	client := &http.Client{
		Timeout: sourceTimeout,
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP 状态码：%d", resp.StatusCode)
	}

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	return extractIPs(string(data)), nil
}

// 合并本地及选定的网络来源，并对所有 IPv4 去重。
func loadAllIPs(customSourceMode bool, customURLs []string) ([]string, error) {
	all := make([]string, 0)
	seen := make(map[string]bool)

	addIPs := func(ips []string) {
		for _, ip := range ips {
			if !seen[ip] {
				seen[ip] = true
				all = append(all, ip)
			}
		}
	}

	fmt.Println("正在读取 IP 来源...")

	localIPs, err := loadLocalIPs("ips.txt")
	if err != nil {
		fmt.Printf("读取本地 ips.txt 失败，跳过：%v\n", err)
	} else {
		addIPs(localIPs)
	}

	urls := sourceURLs
	if customSourceMode {
		urls = customURLs
	}

	for _, sourceURL := range urls {
		fmt.Printf("正在读取：%s\n", sourceURL)

		ips, err := fetchIPs(sourceURL)
		if err != nil {
			fmt.Printf("读取失败，跳过此来源：%v\n", err)
			continue
		}

		fmt.Printf("该来源识别到 %d 个唯一 IPv4\n", len(ips))
		addIPs(ips)
	}

	if len(all) == 0 {
		return nil, fmt.Errorf("所有来源均未取得有效 IPv4")
	}

	fmt.Printf("全部来源合并去重后：%d 个唯一 IPv4\n\n", len(all))
	return all, nil
}

func makeClient(ip string, timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{
				InsecureSkipVerify: true,
			},
			DisableCompression: true,
			DialContext: func(
				ctx context.Context, _, _ string,
			) (net.Conn, error) {
				return (&net.Dialer{
					Timeout: connectTimeout,
				}).DialContext(
					ctx, "tcp", net.JoinHostPort(ip, "443"),
				)
			},
		},
		CheckRedirect: func(
			*http.Request, []*http.Request,
		) error {
			return http.ErrUseLastResponse
		},
	}
}

func validateURL(raw string) error {
	u, err := url.ParseRequestURI(raw)
	if err != nil {
		return fmt.Errorf("测速地址无效：%w", err)
	}
	if u.Scheme != "https" || u.Hostname() == "" {
		return fmt.Errorf("测速地址必须是完整的 HTTPS 地址")
	}
	if u.Port() != "" && u.Port() != "443" {
		return fmt.Errorf("当前 IP 直连模式仅支持 HTTPS 443 端口")
	}
	return nil
}

// 第一阶段：HTTPS 连通性检测，延迟统计到收到响应头为止。
func checkIP(ip, target string) (Result, bool) {
	r := Result{IP: ip}

	ctx, cancel := context.WithTimeout(
		context.Background(), connectTimeout,
	)
	defer cancel()

	req, err := http.NewRequestWithContext(
		ctx, http.MethodGet, target, nil,
	)
	if err != nil {
		return r, false
	}
	req.Header.Set("User-Agent", "Mozilla/5.0")

	start := time.Now()
	resp, err := makeClient(ip, connectTimeout).Do(req)
	r.Latency = time.Since(start)

	if err != nil {
		return r, false
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 400 {
		return r, false
	}

	return r, true
}

type readResult struct {
	n   int
	err error
}

func downloadTest(ip, target string) (Result, error) {
	r := Result{IP: ip}

	ctx, cancel := context.WithTimeout(
		context.Background(), testDuration,
	)
	defer cancel()

	req, err := http.NewRequestWithContext(
		ctx, http.MethodGet, target, nil,
	)
	if err != nil {
		return r, err
	}

	req.Header.Set("User-Agent", "Mozilla/5.0")
	req.Header.Set(
		"Range",
		fmt.Sprintf("bytes=0-%d", downloadLimit-1),
	)

	resp, err := makeClient(ip, testDuration).Do(req)
	if err != nil {
		return r, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK &&
		resp.StatusCode != http.StatusPartialContent {
		return r, fmt.Errorf("HTTP 状态码：%d", resp.StatusCode)
	}

	start := time.Now()
	var firstData, lastData time.Time
	var total int64
	buf := make([]byte, readBufferSize)

	for time.Since(start) < testDuration &&
		total < downloadLimit {

		b := buf
		if left := downloadLimit - total; int64(len(b)) > left {
			b = b[:int(left)]
		}

		ch := make(chan readResult, 1)
		go func(b []byte) {
			n, err := resp.Body.Read(b)
			ch <- readResult{n: n, err: err}
		}(b)

		wait := idleTimeout
		if !lastData.IsZero() {
			wait -= time.Since(lastData)
		}
		if wait <= 0 {
			cancel()
			break
		}

		timer := time.NewTimer(wait)

		select {
		case rr := <-ch:
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}

			if rr.n > 0 {
				now := time.Now()
				if firstData.IsZero() {
					firstData = now
				}
				lastData = now
				total += int64(rr.n)
			}

			if rr.err != nil {
				if rr.err != io.EOF && total == 0 {
					return r, rr.err
				}
				goto finished
			}

			if rr.n == 0 {
				time.Sleep(10 * time.Millisecond)
			}

		case <-timer.C:
			cancel()
			goto finished

		case <-ctx.Done():
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
			goto finished
		}
	}

finished:
	if total < minDownloadBytes {
		return r, fmt.Errorf("有效下载数据不足 64 KiB")
	}

	used := lastData.Sub(start).Seconds()
	if used < minMeasureTime.Seconds() {
		used = minMeasureTime.Seconds()
	}

	r.Bytes = total
	r.Used = used
	r.Speed = float64(total) / used / 1024 / 1024
	return r, nil
}

// 只显示已完成数量/总数量。
func printProgress(done, total int) {
	if total <= 0 {
		return
	}

	fmt.Printf("\r%d/%d", done, total)
}

// 这里沿用原始 runStage 进度更新逻辑。
func runStage(
	title string,
	ips []string,
	workers int,
	target string,
) []Result {
	fmt.Println()
	fmt.Println(title)

	if len(ips) == 0 {
		fmt.Println("没有需要检测的 IP")
		return nil
	}

	if workers > len(ips) {
		workers = len(ips)
	}

	jobs := make(chan string)
	results := make(chan Result, len(ips))
	var wg sync.WaitGroup
	var completed atomic.Int64

	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			for ip := range jobs {
				r, ok := checkIP(ip, target)
				if ok {
					results <- r
				}

				done := completed.Add(1)
				printProgress(int(done), len(ips))
			}
		}()
	}

	for _, ip := range ips {
		jobs <- ip
	}
	close(jobs)

	wg.Wait()
	close(results)

	fmt.Print("\r\033[K")
	fmt.Println()

	var passed []Result
	for r := range results {
		passed = append(passed, r)
	}

	return passed
}

func main() {
	if len(os.Args) < 5 {
		fmt.Println("用法：./sss 测速地址 连通性并发数 下载测速并发数 延迟最低IP数量 [no [IP来源URL...]]")
		fmt.Println("默认示例：./sss https://cesu.dxgees.ggff.net 300 100 20")
		fmt.Println("指定来源：./sss https://cesu.dxgees.ggff.net 300 100 20 no https://example.com/ip1.txt https://example.com/ip2.txt")
		fmt.Println("仅本地文件：./sss https://cesu.dxgees.ggff.net 300 100 20 no")
		os.Exit(1)
	}

	target := strings.TrimSpace(os.Args[1])
	connectN, e1 := strconv.Atoi(os.Args[2])
	downloadN, e2 := strconv.Atoi(os.Args[3])
	topN, e3 := strconv.Atoi(os.Args[4])

	customSourceMode := false
	var customSourceURLs []string

	if len(os.Args) > 5 {
		if os.Args[5] != "no" {
			fmt.Println("可选参数必须使用 no")
			os.Exit(1)
		}

		customSourceMode = true
		customSourceURLs = os.Args[6:]
	}

	if err := validateURL(target); err != nil {
		fmt.Println(err)
		os.Exit(1)
	}
	if e1 != nil || connectN <= 0 {
		fmt.Println("连通性检测并发数必须是正整数")
		os.Exit(1)
	}
	if e2 != nil || downloadN <= 0 {
		fmt.Println("下载测速并发数必须是正整数")
		os.Exit(1)
	}
	if e3 != nil || topN <= 0 {
		fmt.Println("延迟最低 IP 数量必须是正整数")
		os.Exit(1)
	}

	ips, err := loadAllIPs(customSourceMode, customSourceURLs)
	if err != nil {
		fmt.Println("读取 IP 来源失败：", err)
		os.Exit(1)
	}

	fmt.Printf("测速地址：%s\n", target)
	fmt.Printf("有效且去重后的 IPv4：%d 个\n", len(ips))
	fmt.Printf("连通性检测并发数：%d\n", connectN)
	fmt.Printf("下载测速并发数：%d\n", downloadN)
	fmt.Printf("延迟最低筛选数量：%d\n", topN)
	fmt.Println("单 IP 下载上限：50 MiB")
	fmt.Println("单 IP 最长测速时间：10 秒")
	fmt.Println("单 IP 无数据超时：3 秒")
	fmt.Println("最低有效下载量：64 KiB")
	fmt.Println("最低打印速度：0.5M")

	// 第一阶段：检测所有 IP 的 HTTPS 连通性和延迟。
	connected := runStage(
		"第一阶段：HTTPS 连通性检测与延迟测量",
		ips, connectN, target,
	)

	fmt.Printf("\nHTTPS 连通：%d 个 | 不通：%d 个\n",
		len(connected), len(ips)-len(connected))

	if len(connected) == 0 {
		fmt.Println("没有连通的 IP，结束。")
		return
	}

	// 第二阶段：按延迟从低到高排序，选择前 N 个。
	sort.Slice(connected, func(i, j int) bool {
		if connected[i].Latency == connected[j].Latency {
			return connected[i].IP < connected[j].IP
		}
		return connected[i].Latency < connected[j].Latency
	})

	if topN > len(connected) {
		topN = len(connected)
	}
	selected := connected[:topN]

	fmt.Println("\n========== 延迟最低的 IP ==========")
	for i, r := range selected {
		fmt.Printf("%d. %s#%.2fms\n",
			i+1, r.IP,
			float64(r.Latency)/float64(time.Millisecond))
	}

	fmt.Printf("\n第二阶段：仅对延迟最低的 %d 个 IP 下载测速\n", topN)

	if downloadN > len(selected) {
		downloadN = len(selected)
	}

	jobs := make(chan string)
	var wg sync.WaitGroup
	var resultMu, progressMu sync.Mutex
	var completed, failed atomic.Int64
	results := make([]Result, 0, len(selected))

	for i := 0; i < downloadN; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			for ip := range jobs {
				r, err := downloadTest(ip, target)
				if err != nil {
					failed.Add(1)
				} else if r.Speed > 0 {
					resultMu.Lock()
					results = append(results, r)
					resultMu.Unlock()
				}

				done := completed.Add(1)
				progressMu.Lock()
				printProgress(int(done), len(selected))
				progressMu.Unlock()
			}
		}()
	}

	for _, r := range selected {
		jobs <- r.IP
	}
	close(jobs)
	wg.Wait()

	progressMu.Lock()
	fmt.Print("\r\033[K\n")
	progressMu.Unlock()

	// 保留原来的 0.5 MiB/s 过滤规则。
	filtered := make([]Result, 0, len(results))
	for _, r := range results {
		if r.Speed >= minSpeed {
			filtered = append(filtered, r)
		}
	}

	// 保留原来的速度从低到高排序。
	sort.Slice(filtered, func(i, j int) bool {
		return filtered[i].Speed < filtered[j].Speed
	})

	fmt.Println("\n========== 最终统计 ==========")
	fmt.Printf("IP 总数：%d\n", len(ips))
	fmt.Printf("HTTPS 连通：%d\n", len(connected))
	fmt.Printf("HTTPS 不通：%d\n", len(ips)-len(connected))
	fmt.Printf("参与下载测速：%d\n", len(selected))
	fmt.Printf("下载测速成功且速度大于 0：%d\n", len(results))
	fmt.Printf("达到 0.5M 打印标准：%d\n", len(filtered))
	fmt.Printf("下载测速失败：%d\n", failed.Load())

	fmt.Println("\n========== 最终 IP（速度从低到高） ==========")
	if len(filtered) == 0 {
		fmt.Println("没有达到 0.5M 的有效 IP")
		return
	}

	for i, r := range filtered {
	if i > 0 {
		fmt.Print("\n")
	}
	fmt.Printf("%s#%.2fM", r.IP, r.Speed)
}
fmt.Println()
}
