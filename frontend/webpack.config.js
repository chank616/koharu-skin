const path = require('path')
const webpack = require('webpack')
const MiniCssExtractPlugin = require('mini-css-extract-plugin')

module.exports = {
  entry: {
    home: ['./src/styles/home.css', './src/scripts/homePage.ts'],
    app: ['react-hot-loader/patch', './src/index.tsx'],
    style: [
      './src/styles/common.css',
      'admin-lte/dist/css/alt/adminlte.components.min.css',
      'admin-lte/dist/css/alt/adminlte.core.min.css',
      'admin-lte/dist/css/alt/adminlte.pages.min.css',
      'admin-lte/dist/css/alt/adminlte.light.min.css',
      '@fortawesome/fontawesome-free/css/all.min.css',
    ],
  },
  output: {
    path: process.env.FRONTEND_OUTPUT_DIR
      ? path.resolve(process.env.FRONTEND_OUTPUT_DIR)
      : path.resolve(__dirname, '../src/main/resources/static/app'),
    publicPath: '/app/',
    crossOriginLoading: 'anonymous',
    filename: '[name].js',
    chunkFilename: '[id].[contenthash:7].js',
    clean: true,
  },
  module: {
    rules: [
      { test: /\.tsx?$/, loader: 'ts-loader', options: { transpileOnly: true } },
      { test: /\.css$/, use: [MiniCssExtractPlugin.loader, 'css-loader'] },
      { test: /\.(png|webp|svg|woff2?|eot|ttf)$/, type: 'asset' },
    ],
  },
  resolve: {
    extensions: ['.js', '.ts', '.tsx'],
    alias: {
      '@': path.resolve(__dirname, 'src'),
      'react-dom': '@hot-loader/react-dom',
      readline: path.resolve(__dirname, 'src/scripts/cli/readline.ts'),
      prompts: 'prompts/lib/index.js',
      assert: false,
    },
  },
  plugins: [
    new MiniCssExtractPlugin({ filename: '[name].css' }),
    new webpack.ProvidePlugin({ $: 'jquery', jQuery: 'jquery' }),
    new webpack.DefinePlugin({
      'window.Deno': 'true',
      Deno: { args: [], build: {}, version: {} },
      'process.platform': '"browser"',
      __blessing_public_path__: JSON.stringify('/app/'),
    }),
  ],
  experiments: { syncWebAssembly: true },
  stats: 'errors-warnings',
}
